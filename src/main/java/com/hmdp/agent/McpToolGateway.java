package com.hmdp.agent;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Shared JSON-RPC dispatcher for authenticated HTTP bridge and the in-process Agent client. */
@Component
public class McpToolGateway {
 private final CustomerTools business;private final ObjectMapper mapper;private final AtomicLong ids=new AtomicLong();
 public McpToolGateway(CustomerTools business,ObjectMapper mapper){this.business=business;this.mapper=mapper;}
 public JsonNode dispatch(JsonNode request,Long userId){
  ObjectNode response=mapper.createObjectNode();response.put("jsonrpc","2.0");response.set("id",request.path("id"));
  if(!"2.0".equals(request.path("jsonrpc").asText()) || !request.path("method").isTextual())return error(response,-32600,"Invalid request");
  String method=request.path("method").asText();ObjectNode result=mapper.createObjectNode();
  switch(method){
   case "initialize": result.put("protocolVersion","2025-03-26");result.putObject("capabilities").putObject("tools").put("listChanged",false);result.putObject("serverInfo").put("name","hmdp-business-tools").put("version","1.0.0");break;
   case "notifications/initialized":return null;
   case "ping":break;
   case "tools/list":
    ArrayNode tools=result.putArray("tools");for(JsonNode definition:business.definitions()){JsonNode f=definition.path("function");ObjectNode tool=tools.addObject();tool.set("name",f.path("name"));tool.set("description",f.path("description"));tool.set("inputSchema",f.path("parameters"));}break;
   case "tools/call":
    if(userId==null)return error(response,-32602,"Authentication required");
    JsonNode params=request.path("params");String name=params.path("name").asText();boolean known=false;
    for(JsonNode d:business.definitions())if(name.equals(d.path("function").path("name").asText()))known=true;
    if(!known || !params.path("arguments").isObject())return error(response,-32602,"Unknown tool or invalid arguments");
    try{String text=business.execute(name,params.path("arguments").toString(),userId);result.putArray("content").addObject().put("type","text").put("text",text);result.put("isError",text.startsWith("工具参数无效") || text.equals("订单号格式错误"));}
    catch(RuntimeException e){result.put("isError",true);result.putArray("content").addObject().put("type","text").put("text","业务工具暂时不可用");}break;
   default:return error(response,-32601,"Method not found");
  }
  response.set("result",result);return response;
 }
 private ObjectNode error(ObjectNode node,int code,String text){node.putObject("error").put("code",code).put("message",text);return node;}
 private ObjectNode request(String method){ObjectNode n=mapper.createObjectNode();n.put("jsonrpc","2.0");n.put("id",ids.incrementAndGet());n.put("method",method);return n;}
 public JsonNode definitions(){
  JsonNode result=dispatch(request("tools/list"),null).path("result").path("tools");ArrayNode definitions=mapper.createArrayNode();
  for(JsonNode tool:result){ObjectNode f=definitions.addObject();f.put("type","function");ObjectNode body=f.putObject("function");body.set("name",tool.path("name"));body.set("description",tool.path("description"));body.set("parameters",tool.path("inputSchema"));}return definitions;
 }
 public String execute(String name,String arguments,Long userId){
  try{ObjectNode req=request("tools/call");ObjectNode params=req.putObject("params");params.put("name",name);params.set("arguments",mapper.readTree(arguments));
   JsonNode response=dispatch(req,userId);if(response.has("error"))return "工具请求无效";return response.path("result").path("content").path(0).path("text").asText();
  }catch(java.io.IOException e){return "工具参数无效";}
 }
}
