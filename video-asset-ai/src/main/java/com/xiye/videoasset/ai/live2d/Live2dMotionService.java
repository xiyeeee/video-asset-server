package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiye.videoasset.ai.analysis.service.EpisodeAnalysisAccess;
import com.xiye.videoasset.ai.live2d.Live2dComboMapper.ModelVersion;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.ActionAsset;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.CapabilityMapping;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.CapabilityMappingWrite;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.ModelParameter;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.MotionReference;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.MotionVersion;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.NewAsset;
import com.xiye.videoasset.ai.live2d.Live2dMotionMapper.NewVersion;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class Live2dMotionService {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dMotionService.class);
    private static final int MAX_DEFINITION_BYTES = 256 * 1024;
    private static final int MAX_TRACKS = 32;
    private static final int MAX_KEYFRAMES = 512;
    private static final int MAX_CLIPS = 32;
    private static final long MAX_DURATION_MS = 120_000;
    private static final Set<String> TRACK_CODES = Set.of("face", "head", "mouth", "speech");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final Live2dMotionMapper mapper;
    private final Live2dService live2d;
    private final Live2dComboService combos;
    private final EpisodeAnalysisAccess access;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    Live2dMotionService(
            Live2dMotionMapper mapper, Live2dService live2d, Live2dComboService combos,
            EpisodeAnalysisAccess access, ObjectMapper json, TransactionTemplate transaction
    ) {
        this.mapper = mapper;
        this.live2d = live2d;
        this.combos = combos;
        this.access = access;
        this.json = json;
        this.transaction = transaction;
    }

    Page<ActionSummary> list(
            String subject, long project, String type, String category, String keyword,
            String compatibilityStatus, String modelVersionValue, int page, int size
    ) {
        live2d.owned(subject, project);
        long modelVersion = modelVersionValue == null ? 0 : id(modelVersionValue);
        Map<String, Compatibility> compatibilityByVersion = new HashMap<>();
        List<ActionAsset> candidates = new ArrayList<>();
        if (compatibilityStatus == null) {
            candidates = mapper.assets(project, type, category, cleanKeyword(keyword), size, offset(page, size));
        } else {
            if (modelVersion == 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            combos.ensureModelVersion(subject, project, modelVersion);
            long totalAssets = mapper.assetCount(project, type, category, cleanKeyword(keyword));
            int batchSize = 500;
            for (long nextOffset = 0; nextOffset < totalAssets; nextOffset += batchSize) {
                candidates.addAll(mapper.assets(project, type, category, cleanKeyword(keyword), batchSize,
                        nextOffset));
            }
        }
        List<ActionSummary> result = new ArrayList<>();
        for (ActionAsset asset : candidates) {
            String status = null;
            if (modelVersion > 0 && asset.currentVersionId() != null) {
                MotionVersion version = mapper.publishedVersion(project, asset.currentVersionId());
                if (version != null) {
                    Compatibility compatibility = compatibilityByVersion.computeIfAbsent(version.id(),
                            ignored -> compatibility(subject, project, version.id(), modelVersion));
                    status = compatibility.status();
                }
            }
            if (compatibilityStatus != null && !compatibilityStatus.equals(status)) {
                continue;
            }
            result.add(summary(asset, status));
        }
        if (compatibilityStatus == null) {
            return new Page<>(result, page, size, mapper.assetCount(project, type, category, cleanKeyword(keyword)));
        }
        long total = result.size();
        int start = (int) Math.min((long) (page - 1) * size, result.size());
        int end = Math.min(start + size, result.size());
        return new Page<>(List.copyOf(result.subList(start, end)), page, size, total);
    }

    ActionDetail detail(String subject, long project, String motionId) {
        live2d.owned(subject, project);
        ActionAsset asset = requireAsset(project, motionId);
        MotionVersion current = asset.currentVersionId() == null ? null
                : mapper.publishedVersion(project, asset.currentVersionId());
        MotionVersion draft = asset.draftVersionId() == null ? null
                : mapper.version(project, asset.id(), asset.draftVersionId());
        return new ActionDetail(summary(asset, null), view(current), view(draft));
    }

    Page<MotionVersionView> versions(String subject, long project, String motionId, int page, int size) {
        live2d.owned(subject, project);
        requireAsset(project, motionId);
        List<MotionVersionView> published = mapper.versions(project, motionId).stream()
                .filter(version -> "PUBLISHED".equals(version.status()))
                .map(this::view).toList();
        int start = (int) Math.min((long) (page - 1) * size, published.size());
        int end = Math.min(start + size, published.size());
        return new Page<>(List.copyOf(published.subList(start, end)), page, size, published.size());
    }

    VersionDetail version(String subject, long project, String versionId) {
        live2d.owned(subject, project);
        MotionVersion version = mapper.publishedVersion(project, versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
        }
        ActionAsset asset = requireAsset(project, version.motionId());
        return new VersionDetail(summary(asset, null), view(version), definition(version),
                referenceViews(mapper.references(version.id())));
    }

    ActionDetail create(String subject, long project, CreateRequest request) {
        long started = System.nanoTime();
        live2d.owned(subject, project);
        long user = access.requireActiveUser(subject);
        String actionType = type(request.actionType());
        if (!Set.of("COMBO", "CUSTOM").contains(actionType)) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        MotionDefinition definition = normalizeDefinition(project, actionType, request.definition());
        String assetId = newId();
        String draftId = newId();
        String actionCode = "custom-" + assetId;
        String definitionJson = write(definition);
        String hash = hash(definitionJson);
        Boolean created = transaction.execute(status -> {
            mapper.insertAsset(new NewAsset(assetId, project, actionCode, actionType,
                    category(request.category()), text(request.name(), 150), text(request.description(), 500)));
            mapper.insertDraft(new NewVersion(draftId, assetId, 1, definitionJson, hash, user));
            if (mapper.setDraft(project, assetId, draftId) != 1) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
            }
            replaceReferences(draftId, definition);
            return true;
        });
        if (!Boolean.TRUE.equals(created)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        LOG.info("Live2D 动作草稿已创建 projectId={} motionId={} actionType={} durationMs={}",
                project, assetId, actionType, elapsed(started));
        return detail(subject, project, assetId);
    }

    ActionDetail duplicate(String subject, long project, String motionId, DuplicateRequest request) {
        live2d.owned(subject, project);
        ActionAsset sourceAsset = requireAsset(project, motionId);
        MotionVersion source = mapper.publishedVersion(project, request.sourceVersionId());
        if (source == null || !sourceAsset.id().equals(source.motionId())) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
        }
        MotionDefinition definition = definition(source);
        return create(subject, project, new CreateRequest("CUSTOM", request.category(), request.name(),
                "从 " + source.motionId() + " 另存", definition));
    }

    ActionDetail saveDraft(String subject, long project, String motionId, DraftRequest request) {
        long started = System.nanoTime();
        live2d.owned(subject, project);
        long user = access.requireActiveUser(subject);
        ActionAsset before = requireAsset(project, motionId);
        if (before.official() || !Set.of("COMBO", "CUSTOM").contains(before.actionType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        MotionDefinition definition = normalizeDefinition(project, before.actionType(), request.definition());
        String definitionJson = write(definition);
        String definitionHash = hash(definitionJson);
        try {
            transaction.executeWithoutResult(status -> {
            ActionAsset asset = mapper.lockOwnedAsset(project, motionId);
            if (asset == null) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
            }
            if (asset.revision() != request.expectedRevision()) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
            }
            if (asset.draftVersionId() == null) {
                String draftId = newId();
                mapper.insertDraft(new NewVersion(draftId, motionId, request.expectedRevision() + 1,
                        definitionJson, definitionHash, user));
                if (mapper.setDraft(project, motionId, draftId) != 1) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
                }
                replaceReferences(draftId, definition);
            } else {
                MotionVersion draft = mapper.lockDraft(project, motionId);
                if (draft == null || draft.revision() != request.expectedRevision()
                        || mapper.updateDraft(motionId, draft.id(), request.expectedRevision(), definitionJson,
                        definitionHash) != 1) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
                }
                if (mapper.advanceRevision(project, motionId, request.expectedRevision()) != 1) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
                }
                replaceReferences(draft.id(), definition);
            }
            });
        } catch (RuntimeException exception) {
            LOG.warn("Live2D 动作草稿保存失败 projectId={} motionId={} revision={} errorCode={} exceptionType={}",
                    project, motionId, request.expectedRevision(), code(exception),
                    exception.getClass().getSimpleName());
            throw exception;
        }
        LOG.info("Live2D 动作草稿已保存 projectId={} motionId={} revision={} durationMs={}", project, motionId,
                request.expectedRevision() + 1, elapsed(started));
        return detail(subject, project, motionId);
    }

    VersionDetail publish(String subject, long project, String motionId, PublishRequest request) {
        long started = System.nanoTime();
        live2d.owned(subject, project);
        ActionAsset before = requireAsset(project, motionId);
        if (before.official()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        MotionVersion draft = mapper.lockDraft(project, motionId);
        if (draft == null || draft.revision() != request.expectedRevision()) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
        }
        MotionDefinition definition = normalizeDefinition(project, before.actionType(), definition(draft));
        Long number = transaction.execute(status -> {
            ActionAsset asset = mapper.lockOwnedAsset(project, motionId);
            MotionVersion lockedDraft = mapper.lockDraft(project, motionId);
            if (asset == null || lockedDraft == null || asset.draftVersionId() == null
                    || lockedDraft.revision() != request.expectedRevision()) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
            }
            if (mapper.advanceSequence(project, motionId) != 1) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
            }
            long versionNumber = mapper.versionSequence(project, motionId);
            if (mapper.publishVersion(motionId, lockedDraft.id(), versionNumber) != 1
                    || mapper.setPublishedVersion(project, motionId, lockedDraft.id()) != 1) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
            }
            return versionNumber;
        });
        MotionVersion published = before.draftVersionId() == null ? null
                : mapper.publishedVersion(project, before.draftVersionId());
        if (published == null || number == null) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
        }
        LOG.info("Live2D 动作版本已发布 projectId={} motionId={} versionId={} versionNumber={} durationMs={}",
                project, motionId, published.id(), number, elapsed(started));
        return new VersionDetail(summary(requireAsset(project, motionId), null), view(published), definition,
                referenceViews(mapper.references(published.id())));
    }

    Compatibility compatibility(String subject, long project, String motionVersionId, long modelVersionId) {
        long started = System.nanoTime();
        live2d.owned(subject, project);
        combos.ensureModelVersion(subject, project, modelVersionId);
        MotionVersion version = mapper.publishedVersion(project, motionVersionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
        }
        Map<String, CapabilityMapping> mappings = capabilityMappings(subject, project, modelVersionId, false);
        List<MotionReference> references = mapper.references(version.id());
        List<Control> controls = collectControls(project, version, references, new HashSet<>(), null, null, 0,
                definition(version).durationMs());
        Compatibility result = assess(project, modelVersionId, version, controls, references, mappings);
        LOG.info("Live2D 动作兼容性已计算 projectId={} motionId={} motionVersionId={} "
                        + "modelVersionId={} status={} durationMs={}",
                project, version.motionId(), version.id(), modelVersionId, result.status(), elapsed(started));
        return result;
    }

    CapabilitySnapshot capabilities(String subject, long project, long modelVersionId, boolean refresh) {
        live2d.owned(subject, project);
        Map<String, CapabilityMapping> storedMappings;
        try {
            storedMappings = capabilityMappings(subject, project, modelVersionId, refresh);
        } catch (BusinessException exception) {
            if (exception.errorCode() != ErrorCode.LIVE2D_MOTION_COMPATIBILITY_UNAVAILABLE) {
                throw exception;
            }
            storedMappings = new HashMap<>();
            for (CapabilityMapping mapping : mapper.capabilityMappings(modelVersionId)) {
                storedMappings.put(mapping.capabilityId(), mapping);
            }
        }
        List<CapabilityView> mappings = storedMappings.values().stream()
                .sorted(Comparator.comparing(CapabilityMapping::capabilityId))
                .map(mapping -> new CapabilityView(Long.toString(modelVersionId), mapping.capabilityId(),
                        mapping.status(), readStrings(mapping.parameterIds()),
                        readStrings(mapping.missingParameterIds()), jsonStrings(readJson(mapping.evidenceJson())),
                        readAssets(mapping.assetRefsJson()), jsonStrings(readJson(mapping.partIdsJson())),
                        mapping.reason(), mapping.analysisFingerprint(), mapping.source(), mapping.updatedAt()))
                .toList();
        Live2dMotionMapper.AnalysisFailure failure = mapper.analysisFailure(modelVersionId);
        List<ModelParameterView> parameters = mapper.modelParameters(modelVersionId).stream()
                .map(parameter -> new ModelParameterView(parameter.id(), parameter.min(), parameter.max(),
                        parameter.defaultValue(), parameter.active(), parameter.activeDecreasing(),
                        parameter.activeIncreasing()))
                .toList();
        return new CapabilitySnapshot(Long.toString(modelVersionId), failure == null ? "READY" : "FAILED",
                failure == null ? null : failure.errorCode(), failure == null ? null : failure.errorMessage(),
                parameters, mappings);
    }

    CapabilitySnapshot updateCapability(
            String subject, long project, long modelVersionId, String capabilityId, MappingRequest request
    ) {
        live2d.owned(subject, project);
        long user = access.requireActiveUser(subject);
        combos.ensureModelVersion(subject, project, modelVersionId);
        Map<String, CapabilityMapping> mappings = capabilityMappings(subject, project, modelVersionId, false);
        CapabilityMapping current = mappings.get(capabilityId);
        if (current == null || !Set.of("CANDIDATE", "CONFIRMED").contains(request.status())
                || request.parameterIds() == null || request.parameterIds().isEmpty()) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        Map<String, ModelParameter> parameters = new HashMap<>();
        for (ModelParameter parameter : mapper.modelParameters(modelVersionId)) {
            parameters.put(parameter.id(), parameter);
        }
        boolean known = request.parameterIds().stream().allMatch(parameters::containsKey);
        boolean playable = request.parameterIds().stream()
                .allMatch(id -> Boolean.TRUE.equals(parameters.get(id).active()));
        if (!known || ("CONFIRMED".equals(request.status()) && !playable)) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        if (mapper.updateCapabilityMapping(modelVersionId, capabilityId, request.status(),
                write(request.parameterIds()), "[]", "CONFIRMED".equals(request.status())
                        ? "用户按当前模型版本确认映射" : "用户标记候选映射", user) != 1) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_VERSION_CONFLICT);
        }
        LOG.info("Live2D 模型能力映射已更新 projectId={} modelVersionId={} capabilityId={} status={} userId={}",
                project, modelVersionId, capabilityId, request.status(), user);
        return capabilities(subject, project, modelVersionId, false);
    }

    private Map<String, CapabilityMapping> capabilityMappings(
            String subject, long project, long modelVersionId, boolean refresh
    ) {
        combos.ensureModelVersion(subject, project, modelVersionId);
        List<CapabilityMapping> current = mapper.capabilityMappings(modelVersionId);
        if (refresh || current.isEmpty()) {
            long started = System.nanoTime();
            JsonNode observed;
            try {
                observed = combos.inspectModelAnalysis(subject, project, modelVersionId);
            } catch (Live2dComboPython.AnalysisFailure exception) {
                String errorMessage = textLimit(exception.getMessage(), 500);
                mapper.recordAnalysisFailure(modelVersionId, exception.code(), errorMessage);
                LOG.warn("Live2D 模型能力分析失败 projectId={} modelVersionId={} errorCode={}",
                        project, modelVersionId, exception.code());
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_COMPATIBILITY_UNAVAILABLE);
            }
            transaction.executeWithoutResult(status -> {
                mapper.clearPythonMappings(modelVersionId);
                for (JsonNode parameter : observed.path("parameters")) {
                    String parameterId = parameter.path("id").asText();
                    if (parameterId.isBlank()) {
                        continue;
                    }
                    mapper.upsertParameter(new ModelParameter(modelVersionId, parameterId,
                            nullableDouble(parameter, "min"), nullableDouble(parameter, "max"),
                            nullableDouble(parameter, "defaultValue"), nullableBoolean(parameter, "active"),
                            nullableBoolean(parameter, "activeDecreasing"),
                            nullableBoolean(parameter, "activeIncreasing")));
                }
                String fingerprint = observed.path("assetFingerprint").asText("");
                for (JsonNode group : observed.path("capabilities")) {
                    for (JsonNode semantic : group.path("capabilities")) {
                        String semanticId = semantic.path("id").asText();
                        if (semanticId.isBlank()) {
                            continue;
                        }
                        List<String> parameterIds = new ArrayList<>();
                        for (JsonNode parameter : semantic.path("parameters")) {
                            String parameterId = parameter.path("id").asText();
                            if (!parameterId.isBlank()) {
                                parameterIds.add(parameterId);
                            }
                        }
                        Set<String> presentParameters = new HashSet<>(parameterIds);
                        List<String> missingParameterIds = jsonStrings(semantic.path("requiredParameterIds"))
                                .stream().filter(parameterId -> !presentParameters.contains(parameterId)).toList();
                        String observedStatus = semantic.path("status").asText("UNKNOWN");
                        String mappingStatus = switch (observedStatus) {
                            case "CONFIRMED" -> "CONFIRMED";
                            case "CANDIDATE" -> "CANDIDATE";
                            case "MISSING" -> "MISSING";
                            case "UNKNOWN" -> "UNKNOWN";
                            default -> "UNKNOWN";
                        };
                        mapper.upsertCapabilityMapping(new CapabilityMappingWrite(modelVersionId, semanticId,
                                mappingStatus, write(parameterIds), write(missingParameterIds),
                                write(semantic.path("evidence")), write(semantic.path("assets")),
                                write(semantic.path("partIds")), semantic.path("reason").asText(""),
                                fingerprint.isBlank() ? null : fingerprint));
                    }
                }
            });
            mapper.clearAnalysisFailure(modelVersionId);
            current = mapper.capabilityMappings(modelVersionId);
            LOG.info("Live2D 模型能力分析完成 projectId={} modelVersionId={} capabilityCount={} durationMs={}",
                    project, modelVersionId, current.size(), elapsed(started));
        }
        Map<String, CapabilityMapping> result = new HashMap<>();
        for (CapabilityMapping mapping : current) {
            result.put(mapping.capabilityId(), mapping);
        }
        return result;
    }

    private Compatibility assess(long project, long modelVersionId, MotionVersion version,
                                 List<Control> controls, List<MotionReference> references,
                                 Map<String, CapabilityMapping> mappings) {
        List<String> present = new ArrayList<>();
        List<String> candidate = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<MissingSegment> missingSegments = new ArrayList<>();
        List<RuntimeBehavior> runtimeBehaviors = new ArrayList<>();
        Map<String, List<ControlInterval>> intervals = new HashMap<>();
        for (Control control : controls) {
            if ("AUDIO_RMS_TO_MOUTH".equals(control.behavior())) {
                runtimeBehaviors.add(new RuntimeBehavior(control.clipId(), control.behavior(),
                        control.startMs(), control.endMs()));
            }
            CapabilityMapping mapping = mappings.get(control.capabilityId());
            if (mapping == null || "MISSING".equals(mapping.status())) {
                addOnce(missing, control.capabilityId());
                missingSegments.add(new MissingSegment(control.clipId(), control.capabilityId(),
                        control.replacementVersionId()));
                continue;
            }
            if ("PRESENT".equals(mapping.status())) {
                addOnce(present, control.capabilityId());
                addOnce(candidate, control.capabilityId());
                addIntervals(intervals, mapping, control);
                missingSegments.add(new MissingSegment(control.clipId(), control.capabilityId(),
                        control.replacementVersionId()));
                continue;
            }
            if ("UNKNOWN".equals(mapping.status())) {
                addOnce(present, control.capabilityId());
                addIntervals(intervals, mapping, control);
                missingSegments.add(new MissingSegment(control.clipId(), control.capabilityId(),
                        control.replacementVersionId()));
                continue;
            }
            if ("CANDIDATE".equals(mapping.status())) {
                addOnce(candidate, control.capabilityId());
                addIntervals(intervals, mapping, control);
                continue;
            }
            addOnce(confirmed, control.capabilityId());
            addIntervals(intervals, mapping, control);
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<ControlInterval>> entry : intervals.entrySet()) {
            List<ControlInterval> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(ControlInterval::startMs)).toList();
            for (int index = 0; index < sorted.size(); index++) {
                ControlInterval first = sorted.get(index);
                for (int next = index + 1; next < sorted.size(); next++) {
                    ControlInterval second = sorted.get(next);
                    if (second.startMs() >= first.endMs()) {
                        break;
                    }
                    conflicts.add(new Conflict(entry.getKey(), List.of(first.clipId(), second.clipId()),
                            Math.max(first.startMs(), second.startMs()), Math.min(first.endMs(), second.endMs())));
                }
            }
        }
        String status = !missing.isEmpty() || !conflicts.isEmpty() ? "INCOMPATIBLE"
                : !candidate.isEmpty() || !present.isEmpty() ? "PARTIAL" : "COMPATIBLE";
        return new Compatibility(version.id(), Long.toString(modelVersionId), status, present, candidate,
                confirmed, missing, conflicts, missingSegments, deduplicateBehaviors(runtimeBehaviors),
                references.stream().filter(reference -> reference.replacementVersionId() != null)
                        .map(reference -> new Alternative(reference.clipId(), reference.replacementVersionId()))
                        .toList());
    }

    private List<Control> collectControls(long project, MotionVersion version, List<MotionReference> references,
                                          Set<String> visited, String rootClipId, String replacementVersionId,
                                          long offsetMs, long spanMs) {
        if (!visited.add(version.id())) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
        }
        MotionDefinition definition = definition(version);
        List<Control> controls = new ArrayList<>();
        long sourceDuration = Math.max(definition.durationMs(), 1);
        for (TrackDefinition track : definition.tracks()) {
            long trackStart = track.keyframes().isEmpty() ? 0 : track.keyframes().get(0).timeMs();
            long trackEnd = track.keyframes().isEmpty() ? sourceDuration
                    : track.keyframes().get(track.keyframes().size() - 1).timeMs();
            long start = offsetMs + Math.round(trackStart * spanMs / (double) sourceDuration);
            long end = offsetMs + Math.round(trackEnd * spanMs / (double) sourceDuration);
            String clipId = rootClipId == null ? track.trackId() : rootClipId;
            String behavior = track.behavior() == null ? definition.handoffBehavior() : track.behavior();
            controls.add(new Control(clipId, track.capabilityId(), behavior, start, Math.max(start + 1, end),
                    replacementVersionId));
        }
        if (!references.isEmpty()) {
            for (MotionReference reference : references) {
                if ("AUDIO_ASSET".equals(reference.referenceType())) {
                    controls.add(new Control(reference.clipId(), "speech", "AUDIO_RMS_TO_MOUTH",
                            offsetMs + reference.startMs(), offsetMs + reference.startMs() + reference.durationMs(),
                            null));
                    continue;
                }
                MotionVersion child = mapper.referenceVersion(project, version.id(), reference.clipId());
                if (child == null) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
                }
                long childStart = offsetMs + Math.round(reference.startMs() * spanMs / (double) sourceDuration);
                long childSpan = Math.max(1, Math.round(reference.durationMs() * spanMs / (double) sourceDuration));
                controls.addAll(collectControls(project, child, List.of(), visited, reference.clipId(),
                        reference.replacementVersionId(), childStart, Math.min(childSpan, spanMs)));
            }
        }
        visited.remove(version.id());
        return controls;
    }

    private static List<RuntimeBehavior> deduplicateBehaviors(List<RuntimeBehavior> behaviors) {
        Map<String, RuntimeBehavior> unique = new HashMap<>();
        for (RuntimeBehavior behavior : behaviors) {
            unique.put(behavior.clipId() + ":" + behavior.code(), behavior);
        }
        return List.copyOf(unique.values());
    }

    private void addIntervals(Map<String, List<ControlInterval>> intervals, CapabilityMapping mapping,
                              Control control) {
        for (String parameter : readStrings(mapping.parameterIds())) {
            intervals.computeIfAbsent(parameter, ignored -> new ArrayList<>())
                    .add(new ControlInterval(parameter, control.clipId(), control.startMs(), control.endMs()));
        }
    }

    private static void addOnce(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private void replaceReferences(String versionId, MotionDefinition definition) {
        mapper.deleteReferences(versionId);
        List<MotionReference> references = references(definition);
        if (!references.isEmpty()) {
            mapper.insertReferences(versionId, references);
        }
    }

    private static List<ReferenceView> referenceViews(List<MotionReference> references) {
        return references.stream()
                .map(reference -> new ReferenceView(reference.clipId(), reference.referenceType(),
                        reference.sourceMotionVersionId(), reference.sourceAssetId(),
                        reference.replacementVersionId(), reference.trackCode(), reference.startMs(),
                        reference.durationMs(), reference.sourceStartMs()))
                .toList();
    }

    private List<MotionReference> references(MotionDefinition definition) {
        return definition.clips().stream().map(clip -> new MotionReference(null, clip.clipId(),
                clip.referenceType(), clip.sourceMotionVersionId(), clip.sourceAssetId(),
                clip.replacementVersionId(), clip.trackCode(), clip.startMs(), clip.durationMs(),
                clip.sourceStartMs())).toList();
    }

    private MotionDefinition normalizeDefinition(long project, String type, MotionDefinition source) {
        MotionDefinition definition = validateDefinition(type, source);
        Set<String> required = new java.util.TreeSet<>();
        for (TrackDefinition track : definition.tracks()) {
            required.add(track.capabilityId());
        }
        validateReferences(project, type, definition, required);
        List<String> requiredCapabilities = List.copyOf(required);
        if (definition.requiredCapabilities() != null && !definition.requiredCapabilities().isEmpty()
                && !new HashSet<>(definition.requiredCapabilities()).equals(required)) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        return new MotionDefinition(definition.schemaVersion(), definition.durationMs(),
                definition.handoffBehavior(), requiredCapabilities, definition.tracks(), definition.clips());
    }

    private void validateReferences(long project, String type, MotionDefinition definition,
                                    Set<String> requiredCapabilities) {
        Set<String> clipIds = new HashSet<>();
        for (ClipDefinition clip : definition.clips()) {
            if (clip.clipId() == null || !clip.clipId().matches("[a-f0-9]{32}")
                    || !clipIds.add(clip.clipId()) || !TRACK_CODES.contains(clip.trackCode())
                    || clip.startMs() < 0 || clip.durationMs() <= 0
                    || exceeds(clip.startMs(), clip.durationMs(), definition.durationMs())) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
            }
            if ("MOTION_VERSION".equals(clip.referenceType())) {
                MotionVersion source = mapper.publishedVersion(project, clip.sourceMotionVersionId());
                ActionAsset sourceAsset = source == null ? null : mapper.asset(project, source.motionId());
                if (clip.sourceAssetId() != null || source == null || sourceAsset == null
                        || "COMBO".equals(sourceAsset.actionType())) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
                }
                MotionDefinition sourceDefinition = definition(source);
                if (!sourceDefinition.clips().isEmpty() || sourceDefinition.tracks().stream()
                        .noneMatch(track -> track.semanticTrack().equals(clip.trackCode()))) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
                }
                sourceDefinition.tracks().forEach(track -> requiredCapabilities.add(track.capabilityId()));
                if (clip.replacementVersionId() != null) {
                    MotionVersion replacement = mapper.publishedVersion(project, clip.replacementVersionId());
                    if (replacement == null) {
                        throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
                    }
                    MotionDefinition replacementDefinition = definition(replacement);
                    if (!replacementDefinition.clips().isEmpty() || replacementDefinition.tracks().stream()
                            .noneMatch(track -> track.semanticTrack().equals(clip.trackCode()))) {
                        throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
                    }
                }
            } else if ("AUDIO_ASSET".equals(clip.referenceType())) {
                Live2dMotionMapper.AudioAsset audio = clip.sourceAssetId() == null ? null
                        : mapper.audioAsset(project, clip.sourceAssetId());
                if (!"speech".equals(clip.trackCode()) || clip.sourceMotionVersionId() != null
                        || clip.replacementVersionId() != null || audio == null || audio.durationMs() == null
                        || clip.sourceStartMs() == null || clip.sourceStartMs() < 0
                        || exceeds(clip.sourceStartMs(), clip.durationMs(), audio.durationMs())) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_REFERENCE_INVALID);
                }
                requiredCapabilities.add("speech");
            } else {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
            }
        }
        if ("COMBO".equals(type) && definition.clips().isEmpty()) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
    }

    private MotionDefinition validateDefinition(String type, MotionDefinition definition) {
        if (definition == null || definition.schemaVersion() != 1 || definition.durationMs() <= 0
                || definition.durationMs() > MAX_DURATION_MS || definition.tracks() == null
                || definition.tracks().size() > MAX_TRACKS || definition.clips() == null
                || definition.clips().size() > MAX_CLIPS
                || (definition.tracks().isEmpty() && definition.clips().isEmpty())
                || definition.handoffBehavior() != null
                && !"AUDIO_RMS_TO_MOUTH".equals(definition.handoffBehavior())) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        Set<String> trackIds = new HashSet<>();
        for (TrackDefinition track : definition.tracks()) {
            if (track.trackId() == null || track.trackId().isBlank() || !trackIds.add(track.trackId())
                    || !TRACK_CODES.contains(track.semanticTrack()) || track.capabilityId() == null
                    || track.capabilityId().isBlank()
                    || !Set.of("INCREASING", "DECREASING", "BOTH").contains(track.direction())
                    || track.keyframes() == null || track.keyframes().size() > MAX_KEYFRAMES
                    || (track.keyframes().isEmpty()
                    && !"AUDIO_RMS_TO_MOUTH".equals(track.behavior())
                    && !"AUDIO_RMS_TO_MOUTH".equals(definition.handoffBehavior()))
                    || (!track.keyframes().isEmpty() && track.keyframes().size() < 2
                    && !"AUDIO_RMS_TO_MOUTH".equals(track.behavior()))) {
                throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
            }
            long previousTime = -1;
            for (Keyframe keyframe : track.keyframes()) {
                if (keyframe.timeMs() <= previousTime || keyframe.timeMs() > definition.durationMs()
                        || !Double.isFinite(keyframe.value()) || keyframe.value() < 0 || keyframe.value() > 1) {
                    throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
                }
                previousTime = keyframe.timeMs();
            }
        }
        if ("BASIC".equals(type) && definition.tracks().isEmpty()) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        return definition;
    }

    private MotionDefinition definition(MotionVersion version) {
        try {
            return json.readValue(version.definitionJson(), MotionDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
    }

    private ActionAsset requireAsset(long project, String motionId) {
        ActionAsset asset = mapper.asset(project, motionId);
        if (asset == null) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_NOT_FOUND);
        }
        return asset;
    }

    private MotionVersionView view(MotionVersion version) {
        if (version == null) {
            return null;
        }
        return new MotionVersionView(version.id(), version.motionId(), version.versionNumber(), version.status(),
                version.revision(), version.definitionHash(), version.createdAt(), version.publishedAt());
    }

    private ActionSummary summary(ActionAsset asset, String compatibility) {
        MotionVersion current = asset.currentVersionId() == null ? null
                : mapper.publishedVersion(asset.projectId() == null ? 0 : asset.projectId(), asset.currentVersionId());
        return new ActionSummary(asset.id(), asset.code(), asset.actionType(), asset.category(), asset.name(),
                asset.description(), asset.official(), asset.currentVersionId(),
                current == null ? null : current.versionNumber(), asset.draftVersionId(), asset.revision(),
                compatibility);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private List<String> readStrings(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
    }

    private List<String> jsonStrings(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual()) {
                result.add(item.textValue());
            }
        }
        return List.copyOf(result);
    }

    private List<AssetEvidence> readAssets(String value) {
        try {
            return json.readValue(value, new TypeReference<List<AssetEvidence>>() { });
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
    }

    private static Double nullableDouble(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static Boolean nullableBoolean(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isBoolean() ? value.booleanValue() : null;
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder digest = new StringBuilder(64);
            for (byte item : bytes) {
                digest.append(String.format("%02x", item));
            }
            return digest.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static String cleanKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.strip();
    }

    private static String type(String value) {
        if (value == null || !Set.of("COMBO", "CUSTOM").contains(value)) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        return value;
    }

    private static String category(String value) {
        String category = text(value, 48);
        if (!category.matches("[a-z][a-z0-9_-]*")) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        return category;
    }

    private static String text(String value, int max) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.codePointCount(0, result.length()) > max
                || result.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.LIVE2D_MOTION_INVALID);
        }
        return result;
    }

    private static String code(RuntimeException exception) {
        return exception instanceof BusinessException businessException
                ? businessException.errorCode().name() : "INTERNAL_ERROR";
    }

    private static long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0 && Long.toString(parsed).equals(value)) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    private static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String textLimit(String value, int max) {
        if (value == null) {
            return "Python 模型能力分析失败";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean exceeds(long start, long duration, long total) {
        return start > total || duration > total - start;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    record CreateRequest(String actionType, String category, String name, String description,
                         MotionDefinition definition) {
    }

    record DraftRequest(long expectedRevision, MotionDefinition definition) {
    }

    record DuplicateRequest(String sourceVersionId, String category, String name) {
    }

    record PublishRequest(long expectedRevision) {
    }

    record MappingRequest(String status, List<String> parameterIds) {
    }

    record MotionDefinition(int schemaVersion, long durationMs, String handoffBehavior,
                            List<String> requiredCapabilities, List<TrackDefinition> tracks,
                            List<ClipDefinition> clips) {
    }

    record TrackDefinition(String trackId, String semanticTrack, String capabilityId, String direction,
                          String behavior, List<Keyframe> keyframes) {
    }

    record Keyframe(long timeMs, double value) {
    }

    record ClipDefinition(String clipId, String referenceType, String trackCode, String sourceMotionVersionId,
                          Long sourceAssetId, String replacementVersionId, long startMs, long durationMs,
                          Long sourceStartMs) {
    }

    record ActionSummary(String id, String code, String actionType, String category, String name,
                         String description, boolean official, String currentVersionId,
                         Long currentVersionNumber, String draftVersionId, long revision,
                         String compatibilityStatus) {
    }

    record MotionVersionView(String versionId, String motionId, Long versionNumber, String status,
                             long revision, String definitionHash, Instant createdAt, Instant publishedAt) {
    }

    record ActionDetail(ActionSummary action, MotionVersionView currentVersion, MotionVersionView draftVersion) {
    }

    record VersionDetail(ActionSummary action, MotionVersionView version, MotionDefinition definition,
                         List<ReferenceView> references) {
    }

    record ReferenceView(String clipId, String referenceType, String sourceMotionVersionId, Long sourceAssetId,
                         String replacementVersionId, String trackCode, long startMs, long durationMs,
                         Long sourceStartMs) {
    }

    record CapabilityView(String modelVersionId, String capabilityId, String mappingStatus,
                          List<String> parameterIds, List<String> missingParameterIds, List<String> evidence,
                          List<AssetEvidence> assets, List<String> partIds, String reason,
                          String assetFingerprint, String source, Instant updatedAt) {
    }

    record ModelParameterView(String id, Double min, Double max, Double defaultValue, Boolean active,
                              Boolean activeDecreasing, Boolean activeIncreasing) {
    }

    record AssetEvidence(String artifactRef, String kind, String sha256, List<String> parameterIds,
                         Long durationMs) {
    }

    record CapabilitySnapshot(String modelVersionId, String analysisStatus, String errorCode, String errorMessage,
                              List<ModelParameterView> parameters, List<CapabilityView> capabilities) {
    }

    record Conflict(String parameterId, List<String> clipIds, long startMs, long endMs) {
    }

    record MissingSegment(String clipId, String capabilityId, String replacementVersionId) {
    }

    record Alternative(String clipId, String replacementVersionId) {
    }

    record RuntimeBehavior(String clipId, String code, long startMs, long endMs) {
    }

    record Compatibility(String motionVersionId, String modelVersionId, String status,
                         List<String> presentCapabilities, List<String> candidateCapabilities,
                         List<String> confirmedCapabilities, List<String> missingCapabilities,
                         List<Conflict> conflicts, List<MissingSegment> missingSegments,
                         List<RuntimeBehavior> runtimeBehaviors, List<Alternative> alternatives) {
    }

    record Page<T>(List<T> items, int page, int size, long total) {
    }

    private record Control(String clipId, String capabilityId, String behavior, long startMs, long endMs,
                           String replacementVersionId) {
    }

    private record ControlInterval(String parameterId, String clipId, long startMs, long endMs) {
    }
}
