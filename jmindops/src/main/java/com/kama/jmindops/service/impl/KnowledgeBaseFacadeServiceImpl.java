package com.kama.jmindops.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jmindops.converter.KnowledgeBaseConverter;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.KnowledgeBaseMapper;
import com.kama.jmindops.model.dto.KnowledgeBaseDTO;
import com.kama.jmindops.model.entity.KnowledgeBase;
import com.kama.jmindops.model.request.CreateKnowledgeBaseRequest;
import com.kama.jmindops.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jmindops.model.response.CreateKnowledgeBaseResponse;
import com.kama.jmindops.model.response.GetKnowledgeBasesResponse;
import com.kama.jmindops.model.vo.KnowledgeBaseVO;
import com.kama.jmindops.service.KnowledgeBaseFacadeService;
import com.kama.jmindops.security.ResourceAccessService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ResourceAccessService resourceAccessService;
    public KnowledgeBaseFacadeServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper, KnowledgeBaseConverter knowledgeBaseConverter, ResourceAccessService resourceAccessService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.resourceAccessService = resourceAccessService;
    }


    @Override
    public GetKnowledgeBasesResponse getKnowledgeBases() {
        String ownerId = resourceAccessService.currentUserId();
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectAll().stream()
                .filter(knowledgeBase -> ownerId.equals(knowledgeBase.getOwnerId()))
                .toList();
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            try {
                KnowledgeBaseVO vo = knowledgeBaseConverter.toVO(knowledgeBase);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetKnowledgeBasesResponse.builder()
                .knowledgeBases(result.toArray(new KnowledgeBaseVO[0]))
                .build();
    }

    @Override
    public CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        try {
            // 将 CreateKnowledgeBaseRequest 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(request);
            
            // 将 KnowledgeBaseDTO 转换为 KnowledgeBase 实体
            KnowledgeBase knowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);
            
            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            knowledgeBase.setOwnerId(resourceAccessService.currentUserId());
            knowledgeBase.setCreatedAt(now);
            knowledgeBase.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = knowledgeBaseMapper.insert(knowledgeBase);
            if (result <= 0) {
                throw new BizException("创建知识库失败");
            }
            
            // 返回生成的 knowledgeBaseId
            return CreateKnowledgeBaseResponse.builder()
                    .knowledgeBaseId(knowledgeBase.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建知识库时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBase knowledgeBase = resourceAccessService.requireOwnedKnowledgeBase(knowledgeBaseId);
        
        int result = knowledgeBaseMapper.deleteById(knowledgeBaseId);
        if (result <= 0) {
            throw new BizException("删除知识库失败");
        }
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        try {
            // 查询现有的知识库
            KnowledgeBase existingKnowledgeBase = resourceAccessService.requireOwnedKnowledgeBase(knowledgeBaseId);
            
            // 将现有 KnowledgeBase 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(existingKnowledgeBase);
            
            // 使用 UpdateKnowledgeBaseRequest 更新 KnowledgeBaseDTO
            knowledgeBaseConverter.updateDTOFromRequest(knowledgeBaseDTO, request);
            
            // 将更新后的 KnowledgeBaseDTO 转换回 KnowledgeBase 实体
            KnowledgeBase updatedKnowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);
            
            // 保留原有的 ID 和创建时间
            updatedKnowledgeBase.setId(existingKnowledgeBase.getId());
            updatedKnowledgeBase.setCreatedAt(existingKnowledgeBase.getCreatedAt());
            updatedKnowledgeBase.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据库
            int result = knowledgeBaseMapper.updateById(updatedKnowledgeBase);
            if (result <= 0) {
                throw new BizException("更新知识库失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新知识库时发生序列化错误: " + e.getMessage());
        }
    }
}
