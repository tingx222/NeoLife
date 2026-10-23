package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.*;
import com.hmdp.entity.*;
import com.hmdp.utils.UserHolder;
import org.redisson.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import cn.hutool.core.bean.BeanUtil;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SocialService {
    private final IBlogService blogs;
    private final IUserService users;
    private final IFollowService follows;
    private final StringRedisTemplate redis;
    private final RedissonClient locks;
    private final TransactionTemplate tx;
    public SocialService(IBlogService blogs, IUserService users, IFollowService follows,
                         StringRedisTemplate redis, RedissonClient locks, org.springframework.transaction.PlatformTransactionManager manager) {
        this.blogs=blogs; this.users=users; this.follows=follows; this.redis=redis; this.locks=locks; this.tx=new TransactionTemplate(manager);
    }
    public Blog enrich(Blog blog) {
        User author=users.getById(blog.getUserId());
        if(author!=null){blog.setName(author.getNickName());blog.setIcon(author.getIcon());}
        UserDTO user=UserHolder.getUser();
        blog.setIsLike(user!=null && redis.opsForZSet().score("blog:liked:"+blog.getId(),user.getId().toString())!=null);
        return blog;
    }
    public Result detail(Long id){ Blog b=blogs.getById(id); return b==null?Result.fail("笔记不存在"):Result.ok(enrich(b)); }
    public Result page(Long author,int current,boolean hot){
        if(current<1 || current>10000)return Result.fail("页码无效");
        List<Blog> list=blogs.page(new Page<>(current,10),new QueryWrapper<Blog>()
                .eq(author!=null,"user_id",author).orderByDesc(hot?"liked":"create_time").orderByDesc("id")).getRecords();
        list.forEach(this::enrich);return Result.ok(list);
    }
    public Result publish(Blog blog){
        if(blog.getShopId()==null || blog.getTitle()==null || blog.getTitle().trim().isEmpty()
                || blog.getImages()==null || blog.getContent()==null)return Result.fail("商户、标题、图片和正文不能为空");
        blog.setId(null);blog.setUserId(UserHolder.getUser().getId());blog.setLiked(0);blog.setComments(0);
        blog.setCreateTime(null);blog.setUpdateTime(null);
        if(!blogs.save(blog))return Result.fail("发布失败");
        long now=System.currentTimeMillis();
        for(Follow f:follows.list(new QueryWrapper<Follow>().eq("follow_user_id",blog.getUserId())))
            redis.opsForZSet().add("feed:"+f.getUserId(),blog.getId().toString(),now);
        return Result.ok(blog.getId());
    }
    public Result like(Long id){
        Long uid=UserHolder.getUser().getId();RLock lock=locks.getLock("lock:blog-like:"+id);lock.lock();
        try{
            if(blogs.getById(id)==null)return Result.fail("笔记不存在");
            String key="blog:liked:"+id;String member=uid.toString();
            boolean liked=redis.opsForZSet().score(key,member)!=null;
            boolean changed=blogs.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Blog>()
                    .eq("id",id).setSql(liked?"liked = GREATEST(liked - 1, 0)":"liked = liked + 1"));
            if(!changed)return Result.fail("点赞失败");
            if(liked)redis.opsForZSet().remove(key,member);else redis.opsForZSet().add(key,member,System.currentTimeMillis());
            return Result.ok();
        }finally{lock.unlock();}
    }
    public Result likers(Long id){
        Set<String> ids=redis.opsForZSet().range("blog:liked:"+id,0,4);
        return Result.ok(userDtos(ids));
    }
    private List<UserDTO> userDtos(Collection<String> ids){
        if(ids==null || ids.isEmpty())return Collections.emptyList();
        Map<Long,User> byId=users.listByIds(ids).stream().collect(Collectors.toMap(User::getId,u->u));
        List<UserDTO> result=new ArrayList<>();for(String id:ids){User u=byId.get(Long.valueOf(id));if(u!=null)result.add(BeanUtil.copyProperties(u,UserDTO.class));}return result;
    }
    public Result follow(Long target,boolean enabled){
        Long uid=UserHolder.getUser().getId();
        if(uid.equals(target) || users.getById(target)==null)return Result.fail("关注对象无效");
        RLock lock=locks.getLock("lock:follow:"+uid);lock.lock();
        try{
            tx.execute(status->{
                QueryWrapper<Follow> q=new QueryWrapper<Follow>().eq("user_id",uid).eq("follow_user_id",target);
                if(enabled){if(follows.count(q)==0){Follow f=new Follow();f.setUserId(uid);f.setFollowUserId(target);follows.save(f);}}
                else follows.remove(q);return null;
            });
            rebuildFollows(uid);return Result.ok();
        }finally{lock.unlock();}
    }
    private void rebuildFollows(Long uid){
        String key="follows:"+uid;String temporary=key+":"+UUID.randomUUID();
        redis.opsForSet().add(temporary,"_empty");
        for(Follow f:follows.list(new QueryWrapper<Follow>().eq("user_id",uid)))redis.opsForSet().add(temporary,f.getFollowUserId().toString());
        redis.rename(temporary,key);
    }
    public Result common(Long other){
        Long uid=UserHolder.getUser().getId();
        // Refresh from DB also handles existing relationships and loss of Redis cache.
        for(Long id:new TreeSet<>(Arrays.asList(uid,other))){RLock lock=locks.getLock("lock:follow:"+id);lock.lock();try{rebuildFollows(id);}finally{lock.unlock();}}
        Set<String> ids=redis.opsForSet().intersect("follows:"+uid,"follows:"+other);
        if(ids!=null)ids.remove("_empty");return Result.ok(userDtos(ids));
    }
    public Result feed(long max,int offset){
        if(max<0 || offset<0)return Result.fail("分页参数无效");
        Set<ZSetOperations.TypedTuple<String>> tuples=redis.opsForZSet().reverseRangeByScoreWithScores(
                "feed:"+UserHolder.getUser().getId(),0,max,offset,2);
        ScrollResult result=new ScrollResult();result.setList(Collections.emptyList());result.setMinTime(max);result.setOffset(offset);
        if(tuples==null || tuples.isEmpty())return Result.ok(result);
        List<Blog> list=new ArrayList<>();long min=0;int count=0;
        for(ZSetOperations.TypedTuple<String> tuple:tuples){long score=tuple.getScore().longValue();
            if(score==min)count++;else{min=score;count=1;}
            Blog b=blogs.getById(Long.valueOf(tuple.getValue()));if(b!=null)list.add(enrich(b));}
        result.setList(list);result.setMinTime(min);result.setOffset(min==max?offset+count:count);return Result.ok(result);
    }
}
