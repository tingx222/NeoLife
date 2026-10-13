package com.hmdp.controller;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.SocialService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/blog")
public class BlogController {
    private final SocialService social;
    public BlogController(SocialService social){this.social=social;}
    @PostMapping public Result publish(@RequestBody Blog blog){return social.publish(blog);}
    @PutMapping("/like/{id}") public Result like(@PathVariable Long id){return social.like(id);}
    @GetMapping("/{id}") public Result detail(@PathVariable Long id){return social.detail(id);}
    @GetMapping("/likes/{id}") public Result likes(@PathVariable Long id){return social.likers(id);}
    @GetMapping("/hot") public Result hot(@RequestParam(defaultValue="1") int current){return social.page(null,current,true);}
    @GetMapping("/of/me") public Result mine(@RequestParam(defaultValue="1") int current){return social.page(UserHolder.getUser().getId(),current,false);}
    @GetMapping("/of/user") public Result author(@RequestParam Long id,@RequestParam(defaultValue="1") int current){return social.page(id,current,false);}
    @GetMapping("/of/follow") public Result feed(@RequestParam("lastId") long lastId,@RequestParam(defaultValue="0") int offset){return social.feed(lastId,offset);}
}
