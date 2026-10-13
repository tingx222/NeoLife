package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    @org.springframework.beans.factory.annotation.Value("${hmdp.upload-dir:D:/Osborn/redis/code/nginx-1.18.0/html/hmdp/imgs}")
    private String uploadDirectory;

    private File resolveImage(String filename) throws IOException {
        java.nio.file.Path root=java.nio.file.Paths.get(uploadDirectory).toAbsolutePath().normalize();
        java.nio.file.Path resolved=root.resolve(filename.replaceFirst("^/+", "")).normalize();
        if(!resolved.startsWith(root) || resolved.equals(root))throw new IllegalArgumentException("图片路径无效");
        return resolved.toFile();
    }

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(resolveImage(fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) throws IOException {
        File file = resolveImage(filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) throws IOException {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if(suffix==null || !java.util.Arrays.asList("png","jpg","jpeg","gif","webp").contains(suffix.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("只支持常见图片格式");
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = resolveImage(StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
