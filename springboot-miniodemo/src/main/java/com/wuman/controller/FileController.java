package com.wuman.controller;

import com.alibaba.fastjson.JSON;
import com.wuman.constant.CommonConstants;
import com.wuman.pojo.ResultBean;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author lmf
 * @version 1.0
 * @date 2022/11/9 13:24
 */
@RestController
@Slf4j
public class FileController {
    @Autowired
    private MinioClient minioClient;
    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 查询文件列表
     * @return
     * @throws Exception
     */
    @GetMapping("/list")
    public List<Object> list() throws Exception {
        //获取bucket列表
        Iterable<Result<Item>> myObjects = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucketName).build());
        Iterator<Result<Item>> iterator = myObjects.iterator();
        List<Object> items = new ArrayList<>();
        String format = "{'fileName':'%s','fileSize':'%s'}";
        while (iterator.hasNext()) {
            Item item = iterator.next().get();
            items.add(JSON.parse(String.format(format, item.objectName(),
                    formatFileSize(item.size()))));
        }
        return items;
    }

    /**
     * 上传文件
     * @param file
     * @return
     */
    @PostMapping("/upload")
    public ResultBean upload(@RequestParam(name = "file", required = false)
                                     MultipartFile[] file) {
        ResultBean resultBean = ResultBean.newInstance();
        if (file == null || file.length == 0) {
            return resultBean.error("上传文件不能为空");
        }
        List<String> orgFileNameList = new ArrayList<>(file.length);
        for (MultipartFile multipartFile : file) {
            String orgFileName = multipartFile.getOriginalFilename();
            orgFileNameList.add(orgFileName);
            try {
                //文件上传
                InputStream in = multipartFile.getInputStream();
                minioClient.putObject(
                        PutObjectArgs.builder().bucket(bucketName).object(orgFileName).stream(
                                        in, multipartFile.getSize(), -1)
                                .contentType(multipartFile.getContentType())
                                .build());
                in.close();
            } catch (Exception e) {
                log.error(e.getMessage());
                return resultBean.error("上传失败");
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("bucketName", bucketName);
        data.put("fileName", orgFileNameList);
        return resultBean.ok("上传成功", data);
    }

    /**
     * 下载文件
     * @param response
     * @param fileName
     */
    @RequestMapping("/download/{fileName}")
    public void download(HttpServletResponse response, @PathVariable("fileName")
            String fileName) {
        InputStream in = null;
        try {
            // 获取对象信息
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucketName).object(fileName).build());
            response.setContentType(stat.contentType());
            response.setHeader("Content-Disposition", "attachment;filename=" +
                    URLEncoder.encode(fileName, "UTF-8"));
            //文件下载
            in = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .build());
            IOUtils.copy(in, response.getOutputStream());
        } catch (Exception e) {
            log.error(e.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.error(e.getMessage());
                }
            }
        }
    }

    @DeleteMapping("/delete/{fileName}")
    public ResultBean delete(@PathVariable("fileName") String fileName) {
        ResultBean resultBean = ResultBean.newInstance();
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucketName).object(fileName).build());
        } catch (Exception e) {
            log.error(e.getMessage());
            return resultBean.error("删除失败");
        }
        return resultBean.ok("删除成功", null);
    }

    @GetMapping("getUrl/{fileName}")
    public String getUrl(@PathVariable("fileName") String fileName) {
        try {
            // 获取文件访问地址 7天失效
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(fileName).
                    method(Method.GET)
                    .expiry(7, TimeUnit.DAYS).build());
            return url;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private static String formatFileSize(long fileSize) {
        DecimalFormat df = new DecimalFormat("#.00");
        String fileSizeString = "";
        String wrongSize = "0B";
        if (fileSize == 0) {
            return wrongSize;
        }
        if (fileSize < CommonConstants.SIZE_KB) {
            fileSizeString = df.format((double) fileSize) + " B";
        } else if (fileSize < CommonConstants.SIZE_MB) {
            fileSizeString = df.format((double) fileSize / 1024) + " KB";
        } else if (fileSize < CommonConstants.SIZE_GB) {
            fileSizeString = df.format((double) fileSize / 1048576) + " MB";
        } else {
            fileSizeString = df.format((double) fileSize / 1073741824) + " GB";
        }
        return fileSizeString;
    }

}
