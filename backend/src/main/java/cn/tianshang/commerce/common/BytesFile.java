package cn.tianshang.commerce.common;

import java.io.*;
import java.nio.file.Files;
import org.springframework.web.multipart.MultipartFile;

/** 内存里的一个文件，给不经过网页上传的来源（如机器人收到的文件）使用现有的上传处理逻辑。 */
public record BytesFile(String name, String contentType, byte[] bytes) implements MultipartFile {
    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes == null || bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes == null ? 0 : bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), bytes);
    }
}
