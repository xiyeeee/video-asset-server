package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.Combo;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.ComboSummary;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.ComboWrite;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.Compilation;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.CompilationInsert;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.DialogueSource;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.ModelFile;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.ModelVersion;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.SaveRequest;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.SaveRequestInsert;
import com.xiye.videoasset.ai.live2d.Live2dComboPython.Issue;
import com.xiye.videoasset.ai.live2d.Live2dComboPython.Capabilities;
import com.xiye.videoasset.ai.live2d.Live2dComboPython.Capability;
import com.xiye.videoasset.ai.live2d.Live2dComboPython.MotionResult;
import com.xiye.videoasset.ai.live2d.Live2dComboPython.Reply;
import com.xiye.videoasset.ai.live2d.Live2dComboPython.Segment;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class Live2dComboService {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dComboService.class);
    private static final long MAX_DURATION_MS = 120_000;
    private static final int MAX_SEGMENTS = 32;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final long MAX_SAVED_MOTION_BYTES = 1024L * 1024;
    private static final long MAX_AUDIO_BYTES = 512L * 1024 * 1024;
    private static final String BUCKET = "video-assets";
    private final Live2dComboMapper mapper;
    private final Live2dComboPython python;
    private final Live2dService live2d;
    private final AiObjectStorage storage;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Live2dSettings settings;

    Live2dComboService(
            Live2dComboMapper mapper, Live2dComboPython python, Live2dService live2d,
            AiObjectStorage storage, ObjectMapper json, TransactionTemplate transaction, Live2dSettings settings
    ) {
        this.mapper = mapper;
        this.python = python;
        this.live2d = live2d;
        this.storage = storage;
        this.json = json;
        this.transaction = transaction;
        this.settings = settings;
    }

    Catalog catalog(String subject, long project, long modelVersionId, int page, int size) {
        live2d.owned(subject, project);
        ModelVersion version = requireModel(project, modelVersionId);
        String stageName = "catalog-" + newId();
        PreparedRoots roots = preparedRoots(stageName);
        try {
            StagedModel model = stageModel(project, version, roots.output());
            Capabilities capabilities = python.capabilities(Long.toString(modelVersionId), model.manifestRef(),
                    model.mocSha256());
            List<MotionSourceView> motions = motions(project, version);
            List<PresetView> presets = capabilities.capabilities().stream()
                    .filter(capability -> Set.of("blink", "smile", "nod").contains(capability.id()))
                    .map(capability -> new PresetView(capability.id(), capability.supported(),
                            capability.parameterIds(), capability.missingParameterIds()))
                    .toList();
            List<DialogueSourceView> dialogues = mapper.dialogueSources(project, size, offset(page, size)).stream()
                    .map(this::dialogueView).toList();
            return new Catalog(new ModelView(Long.toString(modelVersionId), version.modelName()), presets, motions,
                    new Page<>(dialogues, page, size, mapper.dialogueSourceCount(project)));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        } finally {
            cleanup(roots.output(), stageName);
            cleanup(roots.input(), stageName);
        }
    }

    CompileView compile(String subject, long project, CompileRequest request) {
        live2d.owned(subject, project);
        requireRequestId(request.requestId());
        Timeline timeline = timeline(project, request.modelVersionId(), request.name(), request.durationMs(),
                request.segments());
        String hash = hash(timeline);
        Compilation prior = mapper.compilationByRequest(project, request.requestId());
        if (prior != null) {
            if (!prior.contentHash().equals(hash)) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            return compileView(prior);
        }

        ModelVersion version = requireModel(project, number(request.modelVersionId()));
        String stageName = "compile-" + request.requestId() + "-" + hash.substring(0, 16);
        PreparedRoots roots = preparedRoots(stageName);
        try {
            StagedModel model = stageModel(project, version, roots.output());
            Capabilities capabilities = python.capabilities(request.modelVersionId(), model.manifestRef(),
                    model.mocSha256());
            List<Segment> compilerSegments = compilerSegments(project, version, timeline, roots, capabilities);
            Reply result = python.compile(new Live2dComboPython.Request(request.requestId(), project,
                    request.modelVersionId(), model.manifestRef(), model.mocSha256(), timeline.name(),
                    timeline.durationMs(), compilerSegments));
            PreviewArtifact artifact = "READY".equals(result.status())
                    ? publishPreview(project, request.requestId(), hash, model.mocSha256(), result) : null;
            JsonNode storedPlayback = storedPlayback(result.playbackManifest());
            String compilationId = newId();
            String timelineJson = write(timeline);
            String playbackJson = write(storedPlayback);
            String issuesJson = write(new CompileDiagnostics(result.issues(), result.missingCapabilities()));
            mapper.insertCompilation(new CompilationInsert(compilationId, project, version.modelVersionId(),
                    request.requestId(), hash, timeline.name(), timeline.durationMs(), timelineJson,
                    playbackJson, issuesJson, artifact == null ? null : BUCKET,
                    artifact == null ? null : artifact.key().getBytes(StandardCharsets.UTF_8),
                    artifact == null ? null : artifact.sizeBytes(),
                    artifact == null ? null : artifact.sha256(), result.status()));
            Compilation compilation = mapper.compilationByRequest(project, request.requestId());
            if (compilation == null || !compilation.contentHash().equals(hash)) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            return compileView(compilation);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        } finally {
            cleanup(roots.output(), stageName);
            cleanup(roots.input(), stageName);
        }
    }

    Page<ComboSummary> list(String subject, long project, int page, int size) {
        live2d.owned(subject, project);
        return new Page<>(mapper.combos(project, size, offset(page, size)), page, size, mapper.comboCount(project));
    }

    ComboDetail detail(String subject, long project, String comboId) {
        live2d.owned(subject, project);
        Combo combo = mapper.combo(project, comboId);
        if (combo == null) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
        }
        verifyStoredPlayback(combo.playbackBucket(), combo.playbackKey(), combo.playbackSize());
        ModelVersion version = requireModel(project, combo.modelVersionId());
        JsonNode timeline = readJson(combo.timelineJson());
        JsonNode compiled = enrichPlayback(project, combo.modelVersionId(), readJson(combo.playbackJson()),
                combo.playbackBucket(), combo.playbackKey(), combo.playbackSize());
        String modelEntryUrl = live2d.entryUrl(project, combo.modelVersionId(), version.manifestPath());
        return new ComboDetail(combo.id(), Long.toString(project), Long.toString(combo.modelVersionId()),
                combo.name(), combo.durationMs(), combo.status(), combo.contentHash(), combo.savedAt(),
                combo.updatedAt(), timeline, modelEntryUrl, compiled);
    }

    ComboDetail create(String subject, long project, SaveRequestBody request) {
        live2d.owned(subject, project);
        requireRequestId(request.requestId());
        Timeline timeline = timeline(project, request.modelVersionId(), request.name(), request.durationMs(),
                request.segments());
        Compilation compilation = requireCompilation(project, request.compilationId(), timeline);
        if (!"READY".equals(compilation.status())) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        SaveRequest reserved = reserve(project, request.requestId(), newId(), compilation, "CREATE", null);
        if ("SAVED".equals(reserved.status())) {
            return detail(subject, project, reserved.comboId());
        }
        persist(project, reserved, compilation);
        return detail(subject, project, reserved.comboId());
    }

    ComboDetail update(String subject, long project, String comboId, SaveRequestBody request) {
        live2d.owned(subject, project);
        requireRequestId(comboId);
        requireRequestId(request.requestId());
        Timeline timeline = timeline(project, request.modelVersionId(), request.name(), request.durationMs(),
                request.segments());
        Compilation compilation = requireCompilation(project, request.compilationId(), timeline);
        if (!"READY".equals(compilation.status())) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        SaveRequest reserved = reserve(project, request.requestId(), comboId, compilation, "UPDATE", comboId);
        if ("SAVED".equals(reserved.status())) {
            return detail(subject, project, reserved.comboId());
        }
        persist(project, reserved, compilation);
        return detail(subject, project, reserved.comboId());
    }

    ComboDetail retrySave(String subject, long project, String requestId) {
        live2d.owned(subject, project);
        requireRequestId(requestId);
        // 服务若在上传期间退出，过期的 PROCESSING 请求可由用户显式恢复；正在执行的请求仍返回冲突。
        mapper.failStaleSaveRequest(project, requestId);
        SaveRequest existing = mapper.saveRequest(project, requestId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
        }
        Compilation compilation = mapper.compilation(project, existing.compilationId());
        if (compilation == null || !compilation.contentHash().equals(existing.contentHash())) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
        }
        SaveRequest reserved = reserve(project, requestId, existing.comboId(), compilation,
                existing.operation(), existing.operation().equals("UPDATE") ? existing.comboId() : null);
        if ("SAVED".equals(reserved.status())) {
            return detail(subject, project, reserved.comboId());
        }
        persist(project, reserved, compilation);
        return detail(subject, project, reserved.comboId());
    }

    private SaveRequest reserve(
            long project, String requestId, String comboId, Compilation compilation,
        String operation, String expectedComboId
    ) {
        SaveReservation reservation = transaction.execute(status -> {
            int inserted = mapper.reserveSaveRequest(new SaveRequestInsert(project, requestId, comboId,
                    compilation.id(), compilation.contentHash(), operation));
            SaveRequest request = mapper.lockSaveRequest(project, requestId);
            if (request == null || !request.contentHash().equals(compilation.contentHash())
                    || !request.compilationId().equals(compilation.id()) || !request.operation().equals(operation)
                    || (expectedComboId != null && !expectedComboId.equals(request.comboId()))) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            if ("SAVED".equals(request.status())) {
                return new SaveReservation(request, false);
            }
            if ("UPDATE".equals(operation)) {
                Combo current = mapper.combo(project, request.comboId());
                if (current == null) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
                }
                if (current.modelVersionId() != compilation.modelVersionId()) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
                }
            }
            if (inserted == 1) {
                return new SaveReservation(request, true);
            }
            if ("PROCESSING".equals(request.status())) {
                // 新建请求先占位为处理中；它由当前调用持有，失败后会保留为 FAILED 供重试。
                Combo current = mapper.combo(project, request.comboId());
                if (current != null && current.contentHash().equals(request.contentHash())) {
                    mapper.completeSaveRequest(project, requestId);
                    return new SaveReservation(mapper.lockSaveRequest(project, requestId), false);
                }
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_IN_PROGRESS);
            }
            mapper.retrySaveRequest(project, requestId);
            return new SaveReservation(mapper.lockSaveRequest(project, requestId), true);
        });
        if (reservation == null || reservation.request() == null) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
        }
        if (!reservation.shouldPersist() && !"SAVED".equals(reservation.request().status())) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_IN_PROGRESS);
        }
        return reservation.request();
    }

    private void persist(long project, SaveRequest request, Compilation compilation) {
        String key = "projects/" + project + "/live2d-combos/" + request.comboId() + "/"
                + compilation.contentHash() + ".motion3.json";
        byte[] bytes;
        try {
            if (compilation.artifactBucket() == null || compilation.artifactKey() == null
                    || compilation.artifactSize() == null || compilation.artifactSha256() == null) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            bytes = readStoredObject(compilation.artifactBucket(), key(compilation.artifactKey()),
                    compilation.artifactSize(), MAX_SAVED_MOTION_BYTES);
            if (!compilation.artifactSha256().equals(digest(bytes))) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
            }
            storage.put(BUCKET, key, "application/json", new ByteArrayInputStream(bytes), bytes.length);
            verifyStoredPlayback(BUCKET, key.getBytes(StandardCharsets.UTF_8), bytes.length);
            transaction.executeWithoutResult(status -> {
                SaveRequest locked = mapper.lockSaveRequest(project, request.requestId());
                if (locked == null) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
                }
                if ("SAVED".equals(locked.status())) {
                    return;
                }
                ComboWrite write = new ComboWrite(locked.comboId(), project, compilation.modelVersionId(),
                        compilation.name(), compilation.durationMs(), compilation.contentHash(),
                        compilation.timelineJson(), compilation.playbackJson(), BUCKET,
                        key.getBytes(StandardCharsets.UTF_8), bytes.length);
                if ("CREATE".equals(locked.operation())) {
                    mapper.insertCombo(write);
                } else {
                    int updated = mapper.updateCombo(write);
                    if (updated == 0) {
                        Combo current = mapper.combo(project, locked.comboId());
                        if (current == null || current.modelVersionId() != compilation.modelVersionId()
                                || !current.contentHash().equals(compilation.contentHash())) {
                            throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
                        }
                    }
                }
                if (mapper.completeSaveRequest(project, locked.requestId()) == 0) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
                }
            });
            LOG.info("Live2D 组合动作已保存 requestId={} projectId={} modelVersionId={} comboId={}",
                    request.requestId(), project, compilation.modelVersionId(), request.comboId());
        } catch (IOException | DataAccessException | BusinessException exception) {
            mapper.failSaveRequest(project, request.requestId(), "COMBO_SAVE_FAILED");
            LOG.warn("Live2D 组合动作保存失败 requestId={} projectId={} modelVersionId={} comboId={} exceptionType={}",
                    request.requestId(), project, compilation.modelVersionId(), request.comboId(),
                    exception.getClass().getSimpleName());
            if (exception instanceof BusinessException businessException
                    && businessException.errorCode() == ErrorCode.LIVE2D_COMBO_CONFLICT) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
        }
    }

    private CompileView compileView(Compilation compilation) {
        ModelVersion version = requireModel(compilation.projectId(), compilation.modelVersionId());
        JsonNode playback = enrichPlayback(compilation.projectId(), compilation.modelVersionId(),
                readJson(compilation.playbackJson()), compilation.artifactBucket(), compilation.artifactKey(),
                compilation.artifactSize());
        return new CompileView(compilation.id(), compilation.requestId(), compilation.contentHash(),
                Long.toString(compilation.modelVersionId()), compilation.status(), compilation.durationMs(),
                readJson(compilation.issuesJson()).path("issues"),
                readJson(compilation.issuesJson()).path("missingCapabilities"), live2d.entryUrl(compilation.projectId(),
                compilation.modelVersionId(), version.manifestPath()), playback);
    }

    private Timeline timeline(long project, String versionValue, String requestedName, long duration,
                              List<SegmentInput> requestedSegments) {
        long versionId = number(versionValue);
        String name = requestedName == null ? "" : requestedName.strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 150
                || name.codePoints().anyMatch(Character::isISOControl) || duration <= 0
                || duration > MAX_DURATION_MS || requestedSegments == null || requestedSegments.isEmpty()
                || requestedSegments.size() > MAX_SEGMENTS) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        ModelVersion version = requireModel(project, versionId);
        Map<String, MotionSource> motions = motionSources(project, version);
        Set<String> segmentIds = new HashSet<>();
        List<SegmentInput> segments = requestedSegments.stream()
                .sorted(Comparator.comparingLong(SegmentInput::startTimeMs).thenComparing(SegmentInput::segmentId))
                .toList();
        long speechDurationMs = 0;
        for (SegmentInput segment : segments) {
            if (segment.segmentId() == null || !segment.segmentId().matches("[a-f0-9]{32}")
                    || !segmentIds.add(segment.segmentId()) || segment.durationMs() < 2
                    || segment.startTimeMs() < 0 || exceeds(segment.startTimeMs(), segment.durationMs(), duration)) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
            }
            if ("MOTION".equals(segment.trackType())) {
                if (segment.presetId() != null) {
                    if (!Set.of("blink", "smile", "nod").contains(segment.presetId())
                            || !("preset:" + segment.presetId()).equals(segment.sourceId())
                            || versionId != number(segment.sourceVersionId())) {
                        throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
                    }
                } else {
                    MotionSource motion = motionSource(motions, segment.sourceId(), versionId,
                            segment.sourceVersionId());
                    if (motion.durationMs() != null && segment.durationMs() > motion.durationMs()) {
                        throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
                    }
                }
            } else if ("DIALOGUE".equals(segment.trackType())) {
                if (segment.presetId() != null) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
                }
                speechDurationMs += segment.durationMs();
                if (speechDurationMs > MAX_DURATION_MS) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
                }
                long dialogueId = sourceNumber(segment.sourceId(), "dialogue:");
                long audioId = number(segment.sourceVersionId());
                DialogueSource dialogue = mapper.dialogueSource(project, dialogueId, audioId);
                if (dialogue == null || segment.durationMs() > dialogue.endMs() - dialogue.startMs()) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
                }
            } else {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
            }
        }
        return new Timeline(Long.toString(project), Long.toString(versionId), name, duration, segments);
    }

    private List<Segment> compilerSegments(long project, ModelVersion version, Timeline timeline,
                                           PreparedRoots roots, Capabilities capabilities) throws IOException {
        Map<String, MotionSource> motions = motionSources(project, version);
        Map<String, ModelFile> modelFiles = new HashMap<>();
        for (ModelFile file : mapper.modelFiles(version.modelVersionId())) {
            modelFiles.put(file.path(), file);
        }
        List<Segment> segments = new ArrayList<>(timeline.segments().size());
        for (SegmentInput segment : timeline.segments()) {
            String sourceRef = null;
            String sourceSha256 = null;
            Long sourceStartMs = null;
            Long sourceDurationMs = null;
            if ("MOTION".equals(segment.trackType())) {
                if (segment.presetId() != null) {
                    Capability capability = capabilities.capabilities().stream()
                            .filter(item -> item.id().equals(segment.presetId())).findFirst().orElse(null);
                    // 未绑定的预设仍交给 Python 生成带片段 ID 的缺失能力诊断。
                    if (capability == null) {
                        throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
                    }
                } else {
                    MotionSource motion = motionSource(motions, segment.sourceId(), version.modelVersionId(),
                            segment.sourceVersionId());
                    ModelFile file = modelFiles.get(motion.path());
                    if (file == null) {
                        throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
                    }
                    Path target = roots.output().resolve("actions").resolve(segment.segmentId() + ".motion3.json");
                    copyObject(version.bucket(), Live2dAssets.root(project, version.taskId()) + "files/"
                                    + motion.path(), file.sizeBytes(), MAX_SAVED_MOTION_BYTES, target);
                    sourceRef = relative(settings.outputRoot(), target);
                    sourceSha256 = digest(target);
                    sourceDurationMs = motion.durationMs();
                }
            } else {
                long dialogueId = sourceNumber(segment.sourceId(), "dialogue:");
                DialogueSource dialogue = mapper.dialogueSource(project, dialogueId,
                        number(segment.sourceVersionId()));
                if (dialogue == null) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
                }
                Path target = roots.input().resolve("audio").resolve(segment.segmentId() + ".wav");
                copyObject(dialogue.bucket(), key(dialogue.objectKey()), dialogue.sizeBytes(), MAX_AUDIO_BYTES, target);
                sourceRef = relative(settings.inputRoot(), target);
                sourceSha256 = digest(target);
                sourceStartMs = dialogue.startMs();
                sourceDurationMs = dialogue.endMs() - dialogue.startMs();
            }
            segments.add(new Segment(segment.segmentId(), segment.trackType(), segment.sourceId(),
                    segment.sourceVersionId(), segment.startTimeMs(), segment.durationMs(), sourceStartMs,
                    sourceDurationMs, segment.presetId(), sourceRef, sourceSha256));
        }
        return List.copyOf(segments);
    }

    private Map<String, MotionSource> motionSources(long project, ModelVersion version) {
        JsonNode manifest = storedJson(version, version.manifestPath(), 2 * 1024 * 1024);
        JsonNode groups = manifest.path("FileReferences").path("Motions");
        Map<String, ModelFile> fileMap = new HashMap<>();
        for (ModelFile file : mapper.modelFiles(version.modelVersionId())) {
            fileMap.put(file.path(), file);
        }
        Map<String, MotionSource> sources = new HashMap<>();
        if (groups.isObject()) {
            for (Map.Entry<String, JsonNode> group : groups.properties()) {
                JsonNode items = group.getValue();
                if (!items.isArray()) {
                    continue;
                }
                for (int index = 0; index < items.size(); index++) {
                    String file = items.get(index).path("File").textValue();
                    if (!Live2dArchive.validPath(file)) {
                        continue;
                    }
                    String folder = version.manifestPath().substring(0, version.manifestPath().lastIndexOf('/') + 1);
                    String path = folder + file;
                    ModelFile modelFile = fileMap.get(path);
                    if (modelFile == null || !path.endsWith(".motion3.json")) {
                        continue;
                    }
                    String sourceId = motionId(version.modelVersionId(), path);
                    Long duration = motionDuration(version, path, modelFile.sizeBytes());
                    sources.put(sourceId, new MotionSource(sourceId, version.modelVersionId(), path,
                            group.getKey(), index + 1, duration));
                }
            }
        }
        return Map.copyOf(sources);
    }

    private Long motionDuration(ModelVersion version, String path, long bytes) {
        JsonNode motion = storedJson(version, path, (int) Math.min(bytes, MAX_JSON_BYTES));
        JsonNode seconds = motion.path("Meta").path("Duration");
        if (!seconds.isNumber() || !Double.isFinite(seconds.asDouble()) || seconds.asDouble() <= 0
                || seconds.asDouble() * 1000 > MAX_DURATION_MS) {
            return null;
        }
        return Math.round(seconds.asDouble() * 1000);
    }

    private List<MotionSourceView> motions(long project, ModelVersion version) {
        return motionSources(project, version).values().stream()
                .sorted(Comparator.comparing(MotionSource::group).thenComparingInt(MotionSource::index))
                .map(source -> new MotionSourceView(source.sourceId(), Long.toString(source.versionId()),
                        source.group(), source.index(), source.durationMs(),
                        live2d.fileUrl(project, version.modelVersionId(), source.path())))
                .toList();
    }

    private StagedModel stageModel(long project, ModelVersion version, Path stageRoot) throws IOException {
        JsonNode manifest = storedJson(version, version.manifestPath(), MAX_JSON_BYTES);
        String mocReference = manifest.path("FileReferences").path("Moc").textValue();
        String folder = version.manifestPath().contains("/")
                ? version.manifestPath().substring(0, version.manifestPath().lastIndexOf('/') + 1) : "";
        if (!Live2dArchive.validPath(mocReference)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        String mocPath = folder + mocReference;
        Map<String, ModelFile> files = new HashMap<>();
        for (ModelFile file : mapper.modelFiles(version.modelVersionId())) {
            files.put(file.path(), file);
        }
        ModelFile manifestFile = files.get(version.manifestPath());
        ModelFile mocFile = files.get(mocPath);
        if (manifestFile == null || mocFile == null || !mocPath.endsWith(".moc3")) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
        }
        Path manifestTarget = safeTarget(stageRoot, "model/" + version.manifestPath());
        Path mocTarget = safeTarget(stageRoot, "model/" + mocPath);
        String objectRoot = Live2dAssets.root(project, version.taskId()) + "files/";
        copyObject(version.bucket(), objectRoot + version.manifestPath(), manifestFile.sizeBytes(), MAX_JSON_BYTES,
                manifestTarget);
        copyObject(version.bucket(), objectRoot + mocPath, mocFile.sizeBytes(), Live2dArchive.LIMIT, mocTarget);
        return new StagedModel(relative(settings.outputRoot(), manifestTarget), digest(mocTarget));
    }

    private PreparedRoots preparedRoots(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        try {
            Path outputRoot = prepareRoot(settings.outputRoot());
            Path inputRoot = prepareRoot(settings.inputRoot());
            Path output = outputRoot.resolve(name).normalize();
            Path input = inputRoot.resolve(name).normalize();
            if (!output.startsWith(outputRoot) || !input.startsWith(inputRoot)) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
            }
            Files.createDirectories(output);
            Files.createDirectories(input);
            return new PreparedRoots(output, input);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private static Path prepareRoot(Path configured) throws IOException {
        Path absolute = configured.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        return absolute.toRealPath();
    }

    private void copyObject(String bucket, String objectKey, long expectedSize, long maxSize, Path target)
            throws IOException {
        if (expectedSize <= 0 || expectedSize > maxSize) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        Files.createDirectories(target.getParent());
        long copied = 0;
        try (InputStream input = storage.open(bucket, objectKey);
             java.io.OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > expectedSize || copied > maxSize) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
                }
                output.write(buffer, 0, read);
            }
        }
        if (copied != expectedSize) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private static Path safeTarget(Path root, String relative) {
        if (!Live2dArchive.validPath(relative)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        return target;
    }

    private static String relative(Path configuredRoot, Path target) throws IOException {
        Path root = configuredRoot.toAbsolutePath().normalize().toRealPath();
        Path actual = target.toRealPath();
        if (!actual.startsWith(root) || !Files.isRegularFile(actual)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        String value = root.relativize(actual).toString().replace('\\', '/');
        if (!Live2dArchive.validPath(value)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        return value;
    }

    private static void cleanup(Path directory, String requestId) {
        if (directory != null && Files.exists(directory)) {
            Live2dWorker.clean(directory, requestId);
        }
    }

    private PreviewArtifact publishPreview(long project, String requestId, String contentHash,
                                           String modelMocSha256, Reply result) throws IOException {
        JsonNode compiled = result.playbackManifest().path("compiledMotion");
        String artifactRef = compiled.path("artifactRef").textValue();
        String expectedSha = compiled.path("artifactSha256").textValue();
        String artifactModelSha = compiled.path("modelMocSha256").textValue();
        if (artifactRef == null || expectedSha == null || !modelMocSha256.equals(artifactModelSha)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
        }
        Path artifact = sharedArtifact(settings.outputRoot(), artifactRef, MAX_SAVED_MOTION_BYTES);
        String actualSha = digest(artifact);
        long size = Files.size(artifact);
        if (!expectedSha.equals(actualSha) || size <= 0 || size > MAX_SAVED_MOTION_BYTES) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
        }
        String objectKey = "projects/" + project + "/live2d-combo-previews/" + requestId + "/"
                + contentHash + ".motion3.json";
        storage.put(BUCKET, objectKey, "application/json", artifact, size);
        verifyStoredPlayback(BUCKET, objectKey.getBytes(StandardCharsets.UTF_8), size);
        return new PreviewArtifact(objectKey, size, actualSha);
    }

    private static Path sharedArtifact(Path configuredRoot, String reference, long maxBytes) throws IOException {
        if (!Live2dArchive.validPath(reference)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        Path root = configuredRoot.toAbsolutePath().normalize().toRealPath();
        Path expected = root.resolve(reference).normalize();
        Path actual = expected.toRealPath();
        if (!actual.startsWith(root) || !actual.equals(expected) || !Files.isRegularFile(actual)
                || Files.size(actual) <= 0 || Files.size(actual) > maxBytes) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        return actual;
    }

    private JsonNode storedPlayback(JsonNode source) {
        JsonNode copy = source.deepCopy();
        if (!(copy instanceof ObjectNode playback)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_COMPILER_UNAVAILABLE);
        }
        JsonNode compiled = playback.path("compiledMotion");
        if (compiled instanceof ObjectNode artifact) {
            artifact.remove("artifactRef");
        }
        return playback;
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest()) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private DialogueSourceView dialogueView(DialogueSource source) {
        String audioUrl = audioUrl(source);
        return new DialogueSourceView("dialogue:" + source.dialogueId(), Long.toString(source.audioAssetId()),
                Long.toString(source.episodeId()), source.lineText(), source.startMs(), source.endMs() - source.startMs(),
                audioUrl, AiObjectStorage.PREVIEW_TTL_SECONDS);
    }

    private JsonNode enrichPlayback(long project, long version, JsonNode source,
                                    String artifactBucket, byte[] artifactKey, Long artifactSize) {
        JsonNode modelManifest = source.deepCopy();
        if (!(modelManifest instanceof ObjectNode playback) || !playback.path("segments").isArray()) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
        }
        for (JsonNode value : playback.path("segments")) {
            if (!(value instanceof ObjectNode segment)) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
            }
            String type = segment.path("trackType").asText();
            String sourceId = segment.path("sourceId").asText();
            String sourceVersionId = segment.path("sourceVersionId").asText();
            if ("MOTION".equals(type)) {
                // 内置预设已编译进 Cubism 动作，不需再读取来源文件；自定义动作仍保留来源 URL 供编辑恢复。
                if (!sourceId.startsWith("preset:")) {
                    long sourceVersion = number(sourceVersionId);
                    MotionSource motion = motionSource(motionSources(project, requireModel(project, version)),
                            sourceId, version, sourceVersionId);
                    segment.put("resourceUrl", live2d.fileUrl(project, sourceVersion, motion.path()));
                }
            } else if ("DIALOGUE".equals(type)) {
                DialogueSource dialogue = mapper.dialogueSource(project, sourceNumber(sourceId, "dialogue:"),
                        number(sourceVersionId));
                if (dialogue == null) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
                }
                segment.put("resourceUrl", audioUrl(dialogue));
                segment.put("previewExpiresInSeconds", AiObjectStorage.PREVIEW_TTL_SECONDS);
            } else {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
            }
        }
        JsonNode compiledMotion = playback.path("compiledMotion");
        if (compiledMotion instanceof ObjectNode motion && artifactBucket != null && artifactKey != null
                && artifactSize != null) {
            verifyStoredPlayback(artifactBucket, artifactKey, artifactSize);
            motion.put("resourceUrl", storage.sign(artifactBucket, key(artifactKey), "combo:" + version));
            motion.put("previewExpiresInSeconds", AiObjectStorage.PREVIEW_TTL_SECONDS);
        }
        return playback;
    }

    private Compilation requireCompilation(long project, String compilationId, Timeline timeline) {
        requireRequestId(compilationId);
        Compilation compilation = mapper.compilation(project, compilationId);
        if (compilation == null || !compilation.contentHash().equals(hash(timeline))
                || compilation.modelVersionId() != number(timeline.modelVersionId())) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
        }
        return compilation;
    }

    private ModelVersion requireModel(long project, long version) {
        ModelVersion model = mapper.modelVersion(project, version);
        if (model == null) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
        }
        return model;
    }

    private void verifyStoredPlayback(String bucket, byte[] objectKey, long size) {
        try {
            Long actual = storage.objectSize(bucket, key(objectKey));
            if (actual == null || actual != size) {
                throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private byte[] readStoredObject(String bucket, String objectKey, long expectedSize, long maxSize)
            throws IOException {
        if (expectedSize <= 0 || expectedSize > maxSize) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
        }
        try (InputStream input = storage.open(bucket, objectKey)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize);
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > expectedSize || total > maxSize) {
                    throw new BusinessException(ErrorCode.LIVE2D_COMBO_CONFLICT);
                }
                output.write(buffer, 0, read);
            }
            if (total != expectedSize) {
                throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
            }
            return output.toByteArray();
        }
    }

    private static String digest(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String audioUrl(DialogueSource source) {
        try {
            Long actual = storage.objectSize(source.bucket(), key(source.objectKey()));
            if (actual == null || actual.longValue() != source.sizeBytes()) {
                throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
            }
            return storage.sign(source.bucket(), key(source.objectKey()), Long.toString(source.audioAssetId()));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private JsonNode storedJson(ModelVersion version, String path, int limit) {
        String key = Live2dAssets.root(version.projectId(), version.taskId()) + "files/" + path;
        try (InputStream input = storage.open(BUCKET, key)) {
            byte[] bytes = readLimited(input, limit);
            return json.readTree(bytes);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        if (limit <= 0 || limit > MAX_JSON_BYTES) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) {
                throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String hash(Timeline timeline) {
        try {
            byte[] content = json.writeValueAsBytes(timeline);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_SAVE_FAILED);
        }
    }

    private static MotionSource motionSource(Map<String, MotionSource> motions, String sourceId,
                                             long versionId, String sourceVersionId) {
        if (!Long.toString(versionId).equals(sourceVersionId)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
        }
        MotionSource source = motions.get(sourceId);
        if (source == null || source.versionId() != versionId) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_NOT_FOUND);
        }
        return source;
    }

    private static boolean exceeds(long start, long duration, long total) {
        return start > total || duration > total - start;
    }

    private static long number(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0 && Long.toString(parsed).equals(value)) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
    }

    private static long sourceNumber(String sourceId, String prefix) {
        if (sourceId == null || !sourceId.startsWith(prefix)) {
            throw new BusinessException(ErrorCode.LIVE2D_COMBO_INVALID);
        }
        return number(sourceId.substring(prefix.length()));
    }

    private static String motionId(long versionId, String path) {
        return "motion:" + versionId + ":" + path;
    }

    private static String key(byte[] objectKey) {
        return new String(objectKey, StandardCharsets.UTF_8);
    }

    private static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[a-f0-9]{32}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record MotionSource(String sourceId, long versionId, String path, String group, int index,
                                Long durationMs) {
    }

    private record SaveReservation(SaveRequest request, boolean shouldPersist) {
    }

    record SegmentInput(String segmentId, String trackType, String sourceId, String sourceVersionId,
                        long startTimeMs, long durationMs, String presetId) {
    }

    record CompileRequest(String requestId, String modelVersionId, String name, long durationMs,
                          List<SegmentInput> segments) {
    }

    record SaveRequestBody(String requestId, String compilationId, String modelVersionId, String name,
                           long durationMs, List<SegmentInput> segments) {
    }

    record ModelView(String modelVersionId, String modelName) {
    }

    record PresetView(String presetId, boolean supported, List<String> parameterIds,
                      List<String> missingParameterIds) {
    }

    record MotionSourceView(String sourceId, String sourceVersionId, String group, int index, Long durationMs,
                            String resourceUrl) {
    }

    record DialogueSourceView(String sourceId, String sourceVersionId, String episodeId, String lineText,
                              long sourceStartMs, long durationMs, String resourceUrl,
                              int previewExpiresInSeconds) {
    }

    record Page<T>(List<T> items, int page, int size, long total) {
    }

    record Catalog(ModelView modelVersion, List<PresetView> presets, List<MotionSourceView> motions,
                   Page<DialogueSourceView> dialogues) {
    }

    record CompileView(String compilationId, String requestId, String contentHash, String modelVersionId,
                       String status, long durationMs, JsonNode issues, JsonNode missingCapabilities, String modelEntryUrl,
                       JsonNode playbackManifest) {
    }

    record CompileDiagnostics(List<Issue> issues, List<String> missingCapabilities) {
    }

    record ComboDetail(String comboId, String projectId, String modelVersionId, String name, long durationMs,
                       String status, String contentHash, Instant savedAt, Instant updatedAt, JsonNode timeline,
                       String modelEntryUrl, JsonNode playbackManifest) {
    }

    record Timeline(String projectId, String modelVersionId, String name, long durationMs,
                    List<SegmentInput> segments) {
    }

    private record PreparedRoots(Path output, Path input) {
    }

    private record StagedModel(String manifestRef, String mocSha256) {
    }

    private record PreviewArtifact(String key, long sizeBytes, String sha256) {
    }
}
