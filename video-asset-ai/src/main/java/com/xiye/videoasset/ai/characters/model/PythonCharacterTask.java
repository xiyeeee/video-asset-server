package com.xiye.videoasset.ai.characters.model;

public record PythonCharacterTask(
        String taskId, Status status, Stage stage, Progress progress, Result result, Error error
) {
    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED
    }

    public enum Stage {
        EXTRACTING_AUDIO, DETECTING_SHOTS, EXTRACTING_PREVIEWS, EXTRACTING_FRAMES,
        DETECTING_CHARACTERS, SEGMENTING_CHARACTERS, PREPARING_AUDIO, TRANSCRIBING_DIALOGUE,
        RESOLVING_SHOTS, SELECTING_BACKGROUND_FRAMES, WRITING_RESULTS
    }

    public record Submit(String taskId, String sourceVideoRef, double frameIntervalSeconds) {
    }

    public record Progress(Integer frameCount, Integer detectedCharacterCount) {
    }

    public record Result(String manifestRef, Integer characterAssetCount) {
    }

    public record Error(String code) {
    }
}
