package com.hmdp.controller;
import com.hmdp.agent.McpToolGateway;
import com.hmdp.utils.UserHolder;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController
public class McpController {
 private final McpToolGateway gateway;
 public McpController(McpToolGateway gateway){this.gateway=gateway;}
 @PostMapping("/mcp") public ResponseEntity<JsonNode> call(@RequestBody JsonNode request,@RequestHeader(value="Origin",required=false) String origin){
  // This is an authenticated bridge for the stdio server, not a claim of full Streamable HTTP transport.
  if(origin!=null)return ResponseEntity.status(403).build();
  JsonNode result=gateway.dispatch(request,UserHolder.getUser().getId());return result==null?ResponseEntity.accepted().build():ResponseEntity.ok(result);
 }
}
