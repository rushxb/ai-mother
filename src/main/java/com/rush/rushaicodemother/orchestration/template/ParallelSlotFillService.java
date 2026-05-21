package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 并行 Slot Fill 服务。
 * <p>
 * 通过并行执行模板复制和 Slot Fill 来加速首次生成。
 */
@Slf4j
@Service
public class ParallelSlotFillService {

    private final VueProjectTemplateBootstrapService vueTemplateBootstrapService;
    private final BackendProjectTemplateBootstrapService backendTemplateBootstrapService;
    private final TemplateSlotFillService slotFillService;

    public ParallelSlotFillService(VueProjectTemplateBootstrapService vueTemplateBootstrapService,
                                    BackendProjectTemplateBootstrapService backendTemplateBootstrapService,
                                    TemplateSlotFillService slotFillService) {
        this.vueTemplateBootstrapService = vueTemplateBootstrapService;
        this.backendTemplateBootstrapService = backendTemplateBootstrapService;
        this.slotFillService = slotFillService;
    }

    /**
     * 并行执行模板复制和 Slot 填充。
     *
     * @param templateId  模板 ID
     * @param appId       应用 ID
     * @param userMessage 用户消息
     * @param codeGenType 代码生成类型
     * @return 并行执行结果
     */
    public ParallelSlotFillResult executeInParallel(String templateId, Long appId, String userMessage, CodeGenTypeEnum codeGenType) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 并行执行：模板复制 + Slot 填充准备
            CompletableFuture<?> bootstrapFuture;
            if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
                bootstrapFuture = CompletableFuture.supplyAsync(
                        () -> vueTemplateBootstrapService.bootstrapIfNecessary(appId, userMessage),
                        executor
                );
            } else if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
                bootstrapFuture = CompletableFuture.supplyAsync(
                        () -> backendTemplateBootstrapService.bootstrapIfNecessary(appId),
                        executor
                );
            } else {
                log.warn("不支持的代码生成类型: {}", codeGenType);
                return new ParallelSlotFillResult(null, null, false);
            }

            CompletableFuture<SlotFillResult> slotFillFuture =
                    CompletableFuture.supplyAsync(
                            () -> slotFillService.fillSlots(templateId, appId, userMessage),
                            executor
                    );

            // 等待两个任务完成
            CompletableFuture.allOf(bootstrapFuture, slotFillFuture).join();

            // 获取结果
            SlotFillResult slotFillResult = slotFillFuture.get();

            return new ParallelSlotFillResult(
                    null, // 不再需要 bootstrap 结果
                    slotFillResult,
                    slotFillResult != null
            );
        } catch (Exception e) {
            log.warn("并行 Slot Fill 失败: {}", e.getMessage());
            return new ParallelSlotFillResult(null, null, false);
        }
    }

    /**
     * 并行执行结果。
     */
    public record ParallelSlotFillResult(
            VueProjectTemplateBootstrapService.BootstrapResult bootstrapResult,
            SlotFillResult slotFillResult,
            boolean success
    ) {
        /**
         * 获取填充的 slot 数量。
         */
        public int filledSlotCount() {
            return slotFillResult != null ? slotFillResult.filledSlots().size() : 0;
        }

        /**
         * 获取 patch 操作列表。
         */
        public List<PatchOperation> patchOperations() {
            return slotFillResult != null ? slotFillResult.patchOperations() : new ArrayList<>();
        }

        /**
         * 获取摘要。
         */
        public String summary() {
            if (!success) {
                return "并行 Slot Fill 未成功";
            }
            return String.format("模板已复制，%d 个 slot 已填充", filledSlotCount());
        }
    }
}
