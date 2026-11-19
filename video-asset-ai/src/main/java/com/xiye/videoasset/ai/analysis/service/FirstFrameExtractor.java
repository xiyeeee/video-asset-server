package com.xiye.videoasset.ai.analysis.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FirstFrameExtractor {
    private final String executable;

    FirstFrameExtractor(@Value("${app.analysis.ffmpeg}") String executable) {
        this.executable = executable;
    }

    void extract(Path input, Path output) {
        Process process;
        try {
            // 只读本地 MP4、关闭外部数据引用，并将结果限制为一张缩放后的 JPEG。
            ProcessBuilder builder = new ProcessBuilder(executable, "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-max_alloc", "134217728", "-filter_threads", "1",
                    "-protocol_whitelist", "file", "-f", "mov", "-enable_drefs", "0",
                    "-threads", "2", "-i", input.toString(), "-map", "0:v:0", "-frames:v", "1",
                    "-vf", "scale=1280:720:force_original_aspect_ratio=decrease",
                    "-threads", "2", "-c:v", "mjpeg", "-update", "1", "-y", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            // 媒体解码进程无需数据库、对象存储或消息中间件的凭证。
            builder.environment().keySet().retainAll(Set.of("PATH", "SystemRoot"));
            process = builder.start();
        } catch (IOException exception) {
            throw new FrameProcessingException("FFMPEG_UNAVAILABLE", exception);
        }

        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new FrameProcessingException("FRAME_PROCESS_TIMEOUT");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                throw new FrameProcessingException("FRAME_PROCESS_FAILED");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FrameProcessingException("FRAME_PROCESS_INTERRUPTED", exception);
        }
    }
}
