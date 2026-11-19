package com.xiye.videoasset.media.project.service;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.media.project.model.ProjectVO;
import com.xiye.videoasset.media.project.persistence.ProjectRepository;
import com.xiye.videoasset.media.project.persistence.ProjectRepository.ProjectRow;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public Page list(String subject, String search, int page, int size) {
        long ownerId = activeUserId(subject);
        String keyword = search.strip();
        long offset = (long) (page - 1) * size;
        List<ProjectVO> items = projects.list(ownerId, keyword, size, offset)
                .stream()
                .map(ProjectService::view)
                .toList();
        return new Page(items, page, size, projects.count(ownerId, keyword));
    }

    @Transactional
    public ProjectVO create(String subject, String name, String description) {
        long ownerId = userId(subject);
        long projectId = projects.insert(ownerId, name, description);
        if (projectId == 0) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return getOwned(ownerId, projectId);
    }

    public ProjectVO get(String subject, long projectId) {
        return getOwned(activeUserId(subject), projectId);
    }

    @Transactional
    public ProjectVO update(String subject, long projectId, String name, String description) {
        long ownerId = activeUserId(subject);
        // 更新条件同时限制归属与软删状态，在事务内回读也兼容重复提交相同值。
        projects.update(ownerId, projectId, name, description);
        return getOwned(ownerId, projectId);
    }

    /** 校验当前身份及项目归属，返回已验证的内部用户 ID。 */
    public long requireOwnedProject(String subject, long projectId) {
        long ownerId = activeUserId(subject);
        getOwned(ownerId, projectId);
        return ownerId;
    }

    private long activeUserId(String subject) {
        long ownerId = userId(subject);
        if (!projects.isActiveUser(ownerId)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return ownerId;
    }

    private static long userId(String subject) {
        try {
            long ownerId = Long.parseLong(subject);
            if (ownerId > 0) {
                return ownerId;
            }
        } catch (NumberFormatException ignored) {
            // JWT 的 sub 必须能映射为本系统的用户主键。
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private ProjectVO getOwned(long ownerId, long projectId) {
        return projects.find(ownerId, projectId)
                .map(ProjectService::view)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static ProjectVO view(ProjectRow row) {
        String description = row.description() == null ? "" : row.description();
        return new ProjectVO(Long.toString(row.id()), row.name(), description, row.createdAt(), row.updatedAt());
    }

    public record Page(List<ProjectVO> items, int page, int size, long total) {
    }
}
