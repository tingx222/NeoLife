"""Explicit local benchmark: isolated DB users, Redis test sessions, fresh vouchers, JMeter CLI.
Requires PyYAML (available in this workspace), mysql CLI and JMeter. Secrets stay under ignored tmp/.
Does not benchmark SMS/login. Never resets shared queues or existing activity data.
"""
import argparse, csv, datetime, json, os, pathlib, re, secrets, shutil, socket, subprocess, tempfile, time, urllib.request, urllib.parse, uuid
import xml.etree.ElementTree as X
import yaml

ROOT=pathlib.Path(__file__).resolve().parents[1]

class Redis:
    def __init__(self, settings):
        self.socket=socket.create_connection((settings['host'],settings.get('port',6379)),5)
        self.file=self.socket.makefile('rb')
        if settings.get('password'): self.call('AUTH',settings['password'])
        if settings.get('database'): self.call('SELECT',settings['database'])
    def read(self):
        line=self.file.readline(); kind=line[:1]; body=line[1:-2]
        if kind==b'-': raise RuntimeError('Redis command failed')
        if kind in (b'+',b':'): return body.decode() if kind==b'+' else int(body)
        if kind==b'$':
            n=int(body)
            if n<0:return None
            value=self.file.read(n);self.file.read(2);return value.decode()
        if kind==b'*':return [self.read() for _ in range(int(body))]
        raise RuntimeError('Redis connection closed')
    def call(self,*args):
        parts=[str(x).encode() for x in args]
        self.socket.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in parts))
        return self.read()

def prop(parent,name,value,kind='stringProp'):
    n=X.SubElement(parent,kind,{'name':name});n.text=str(value);return n

def write_plan(path):
    root=X.Element('jmeterTestPlan',{'version':'1.2','properties':'5.0','jmeter':'5.6.3'});tree=X.SubElement(root,'hashTree')
    plan=X.SubElement(tree,'TestPlan',{'guiclass':'TestPlanGui','testclass':'TestPlan','testname':'Isolated order admission benchmark','enabled':'true'})
    args=X.SubElement(plan,'elementProp',{'name':'TestPlan.user_defined_variables','elementType':'Arguments','guiclass':'ArgumentsPanel','testclass':'Arguments','testname':'Variables'});X.SubElement(args,'collectionProp',{'name':'Arguments.arguments'})
    pt=X.SubElement(tree,'hashTree');group=X.SubElement(pt,'ThreadGroup',{'guiclass':'ThreadGroupGui','testclass':'ThreadGroup','testname':'Unique users','enabled':'true'})
    prop(group,'ThreadGroup.num_threads','${__P(threads,10)}');prop(group,'ThreadGroup.ramp_time','${__P(ramp,5)}');prop(group,'ThreadGroup.on_sample_error','continue')
    loop=X.SubElement(group,'elementProp',{'name':'ThreadGroup.main_controller','elementType':'LoopController','guiclass':'LoopControlPanel','testclass':'LoopController','testname':'Loop'})
    prop(loop,'LoopController.continue_forever','false','boolProp');prop(loop,'LoopController.loops','-1')
    gt=X.SubElement(pt,'hashTree');ds=X.SubElement(gt,'CSVDataSet',{'guiclass':'TestBeanGUI','testclass':'CSVDataSet','testname':'Private tokens CSV','enabled':'true'})
    for key,value in {'filename':'${__P(tokensFile)}','fileEncoding':'UTF-8','variableNames':'token','delimiter':',','shareMode':'shareMode.all'}.items():prop(ds,key,value)
    for key,value in {'recycle':'false','stopThread':'true','ignoreFirstLine':'false','quotedData':'false'}.items():prop(ds,key,value,'boolProp')
    X.SubElement(gt,'hashTree');headers=X.SubElement(gt,'HeaderManager',{'guiclass':'HeaderPanel','testclass':'HeaderManager','testname':'Login token','enabled':'true'})
    collection=X.SubElement(headers,'collectionProp',{'name':'HeaderManager.headers'});h=X.SubElement(collection,'elementProp',{'name':'','elementType':'Header'});prop(h,'Header.name','authorization');prop(h,'Header.value','${token}')
    X.SubElement(gt,'hashTree');http=X.SubElement(gt,'HTTPSamplerProxy',{'guiclass':'HttpTestSampleGui','testclass':'HTTPSamplerProxy','testname':'Order admission','enabled':'true'})
    arg=X.SubElement(http,'elementProp',{'name':'HTTPsampler.Arguments','elementType':'Arguments'});X.SubElement(arg,'collectionProp',{'name':'Arguments.arguments'})
    for key,value in {'domain':'127.0.0.1','port':'8080','protocol':'http','method':'POST','path':'/api/voucher-order/seckill/${__P(voucherId)}','implementation':'HttpClient4','connect_timeout':'5000','response_timeout':'15000'}.items():prop(http,'HTTPSampler.'+key,value)
    prop(http,'HTTPSampler.use_keepalive','true','boolProp');prop(http,'HTTPSampler.follow_redirects','false','boolProp')
    ht=X.SubElement(gt,'hashTree');assertion=X.SubElement(ht,'JSR223Assertion',{'guiclass':'TestBeanGUI','testclass':'JSR223Assertion','testname':'Business success required','enabled':'true'})
    prop(assertion,'scriptLanguage','groovy');prop(assertion,'cacheKey','true')
    prop(assertion,'script',"try { def body=new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()); if(body.success != true){AssertionResult.setFailure(true);AssertionResult.setFailureMessage('business_rejected: '+body.errorMsg)} } catch(Exception e){AssertionResult.setFailure(true);AssertionResult.setFailureMessage('invalid_json_response')}")
    X.SubElement(ht,'hashTree');X.indent(root);X.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--execute',action='store_true');parser.add_argument('--jmeter',default=shutil.which('jmeter') or 'D:/develop/apache-jmeter-5.6.3/bin/jmeter.bat');opts=parser.parse_args()
    plan=ROOT/'scripts/jmeter-orders.jmx';write_plan(plan)
    if not opts.execute:print('JMX generated. Use --execute to create fixtures and run.');return
    tag='bench_'+uuid.uuid4().hex[:16];folder=ROOT/'tmp'/tag;folder.mkdir(parents=True);manifest={'tag':tag,'users':[],'tokens':[],'vouchers':[]}
    config=yaml.safe_load((ROOT/'src/main/resources/application.yaml').read_text(encoding='utf-8-sig'))['spring']
    local={}
    for filename in ['application.properties','application-rabbit.properties']:
        f=ROOT/'config'/filename
        if f.exists():
            for line in f.read_text(encoding='utf-8-sig').splitlines():
                if '=' in line and not line.lstrip().startswith('#'):k,v=line.split('=',1);local[k.strip()]=v.strip()
    db=config['datasource'];host,port,database=re.match(r'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)',db['url']).groups()
    def quote(value):return '"'+str(value).replace('\\','\\\\').replace('"','\\"')+'"'
    temp=tempfile.TemporaryDirectory(prefix='hmdp-bench-');credentials=pathlib.Path(temp.name)/'mysql.cnf'
    credentials.write_text('[client]\nhost='+quote(host)+'\nport='+(port or '3306')+'\nuser='+quote(db['username'])+'\npassword='+quote(db['password'])+'\n',encoding='utf-8')
    def sql(text):
        result=subprocess.run(['mysql','--defaults-extra-file='+str(credentials),'--default-character-set=utf8mb4','-N','-B',database],input=text,encoding='utf-8',capture_output=True)
        if result.returncode:raise RuntimeError('MySQL operation failed; fixtures preserved for inspection')
        return result.stdout.strip()
    redis=Redis(config['redis'])
    def broker():
        req=urllib.request.Request('http://127.0.0.1:15672/api/queues/hmdp/hmdp.orders')
        import base64
        auth=local['spring.rabbitmq.username']+':'+local['spring.rabbitmq.password'];req.add_header('Authorization','Basic '+base64.b64encode(auth.encode()).decode())
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
    def http(path,payload=None,token=None):
        headers={'Content-Type':'application/json'}
        if token:headers['authorization']=token
        request=urllib.request.Request('http://127.0.0.1:8080/api'+path,None if payload is None else json.dumps(payload).encode(),headers)
        with urllib.request.urlopen(request,timeout=15) as response:return json.load(response)
    def save():(folder/'private-manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
    assert broker().get('consumers',0)>0,'No Rabbit order consumers'
    assert redis.call('HLEN','seckill:pending')==0,'Existing pending orders: do not benchmark until resolved'
    assert sql('SELECT COUNT(*) FROM tb_shop WHERE id=1;')=='1'
    prefix='000'+str(secrets.randbelow(10000)).zfill(4)
    assert sql("SELECT COUNT(*) FROM tb_user WHERE phone LIKE '"+prefix+"%';")=='0','Fixture phone collision; rerun'
    values=','.join("('%s%04d','%s')"%(prefix,i,tag) for i in range(1000))
    sql('INSERT INTO tb_user(phone,nick_name) VALUES '+values+';')
    manifest['users']=[int(x) for x in sql("SELECT id FROM tb_user WHERE nick_name='"+tag+"' ORDER BY id;").splitlines()];save()
    assert len(manifest['users'])==1000
    for uid in manifest['users']:
        token=uuid.uuid4().hex;redis.call('HSET','login:token:'+token,'id',uid,'nickName',tag);redis.call('EXPIRE','login:token:'+token,3600);manifest['tokens'].append(token)
    save();assert http('/user/me',token=manifest['tokens'][0])['data']['id']==manifest['users'][0]
    report={'run':tag,'jmeter':opts.jmeter,'scenario':'local closed-loop unique-user burst, not a sustained 1500-RPS test','account_count':1000,'authentication':'synthetic DB users and temporary Redis sessions; login/SMS excluded','rounds':[]}
    drained=True
    try:
        for samples,threads,ramp in [(10,2,0),(100,10,0),(500,50,0),(1000,100,0)]:
            now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
            body={'shopId':1,'title':tag+'_'+str(samples),'subTitle':'Isolated load test','rules':'test only','payValue':100,'actualValue':200,'status':1,'stock':samples,'beginTime':(now-datetime.timedelta(minutes=10)).strftime('%Y-%m-%dT%H:%M:%S'),'endTime':(now+datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%S')}
            result=http('/voucher/seckill',body);assert result['success'], 'Voucher creation rejected';vid=int(result['data']);manifest['vouchers'].append(vid);save()
            # Prewarm through the same authenticated read-only MCP business tool? Admission itself must not be pre-consumed.
            # Seed the exact activity snapshot once for this new, untouched fixture to exclude lazy-load from measurements.
            redis.call('HSET','seckill:activity:'+str(vid),'stock',samples,'begin',int((now-datetime.timedelta(minutes=10)).timestamp()*1000),'end',int((now+datetime.timedelta(days=1)).timestamp()*1000))
            csv_path=folder/(str(samples)+'-tokens.csv');csv_path.write_text('\n'.join(manifest['tokens'][:samples])+'\n',encoding='utf-8')
            jtl=folder/(str(samples)+'.jtl');log=folder/(str(samples)+'-jmeter.log');stdout=folder/(str(samples)+'-console.log')
            counters_before=broker().get('message_stats',{});start=time.monotonic();drained=False
            with stdout.open('w',encoding='utf-8') as output:
                task=subprocess.Popen([opts.jmeter,'-n','-t',str(plan),'-l',str(jtl),'-j',str(log),'-Jthreads='+str(threads),'-Jramp='+str(ramp),'-JtokensFile='+str(csv_path),'-JvoucherId='+str(vid),'-Jjmeter.save.saveservice.output_format=csv','-Jjmeter.save.saveservice.assertion_results_failure_message=true'],stdout=output,stderr=subprocess.STDOUT,cwd=ROOT)
                while task.poll() is None:
                    time.sleep(2)
                    if time.monotonic()-start>180:task.terminate();raise RuntimeError('JMeter timed out; fixtures preserved')
                assert task.returncode==0,'JMeter failed: inspect private console log'
            with jtl.open(encoding='utf-8-sig') as file:rows=list(csv.DictReader(file))
            accepted=sum(row['success']=='true' for row in rows);assert len(rows)==samples,'Unexpected sample count'
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                count=int(sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id='+str(vid)+';'))
                if count==accepted:break
                time.sleep(1)
            totals=sql('SELECT COUNT(*),COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id='+str(vid)+';').split('\t')
            stock=int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id='+str(vid)+';'));rstock=int(redis.call('HGET','seckill:activity:'+str(vid),'stock'))
            pending=dict(zip(*(iter(redis.call('HGETALL','seckill:pending:vouchers')),)*2))
            drained=count==accepted and str(vid) not in pending.values()
            times=sorted(int(row['elapsed']) for row in rows);first=min(int(row['timeStamp']) for row in rows);last=max(int(row['timeStamp'])+int(row['elapsed']) for row in rows);span=max((last-first)/1000,.001)
            stats=broker().get('message_stats',{})
            record={'voucher_id':vid,'requests':len(rows),'threads':threads,'max_active_threads':max(int(row.get('allThreads',0)) for row in rows),'ramp_seconds':ramp,'accepted':accepted,'failed':samples-accepted,'sample_window_seconds':span,'admission_rps':round(accepted/span,2),'p95_ms':times[min(len(times)-1,int(len(times)*.95))],'p99_ms':times[min(len(times)-1,int(len(times)*.99))],'committed':count,'unique_buyers':int(totals[1]),'db_stock':stock,'redis_stock':rstock,'completion_seconds_from_jmeter_launch':round(time.monotonic()-start,2),'drained':drained,'rabbit_counter_delta_unaligned':{k:stats.get(k,0)-counters_before.get(k,0) for k in ['publish','deliver_get','ack']}}
            report['rounds'].append(record);(folder/'summary.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(record),flush=True)
            assert accepted==samples and count==samples and int(totals[1])==samples and stock==0 and rstock==0 and drained,'Round failed consistency/success checks; stop scaling'
        report['result']='all rounds passed';report['cleanup']='completed'
        for vid in manifest['vouchers']:
            sql('DELETE FROM tb_voucher_order WHERE voucher_id='+str(vid)+'; DELETE FROM tb_seckill_voucher WHERE voucher_id='+str(vid)+'; DELETE FROM tb_voucher WHERE id='+str(vid)+" AND title LIKE '"+tag+"%';")
            redis.call('DEL','seckill:activity:'+str(vid),'seckill:purchased:'+str(vid))
        sql("DELETE FROM tb_user WHERE nick_name='"+tag+"' AND phone LIKE '"+prefix+"%';")
        for token in manifest['tokens']:redis.call('DEL','login:token:'+token)
        assert sql("SELECT COUNT(*) FROM tb_user WHERE nick_name='"+tag+"';")=='0'
        for vid in manifest['vouchers']:
            assert sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id='+str(vid)+';')=='0'
            assert sql('SELECT COUNT(*) FROM tb_voucher WHERE id='+str(vid)+';')=='0'
        assert all(redis.call('EXISTS','login:token:'+token)==0 for token in manifest['tokens'])
        report['cleanup_verified']=True
        report['rabbit_statistics_note']='Management counters are periodically sampled; per-round deltas are unaligned and not exact per-round delivery counts.'
        for file in folder.glob('*-tokens.csv'):file.unlink()
        manifest['tokens']=[];manifest['cleanup']='completed';save()
        report_path=ROOT/'docs'/('JMeter-result-'+tag+'.json');report_path.write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('Sanitized report: '+str(report_path),flush=True)
    finally:temp.cleanup()

if __name__=='__main__':main()
