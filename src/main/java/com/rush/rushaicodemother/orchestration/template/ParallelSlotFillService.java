package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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
    private final FullStackPortAllocator fullStackPortAllocator;
    private final TemplateSlotFillService slotFillService;

    public ParallelSlotFillService(VueProjectTemplateBootstrapService vueTemplateBootstrapService,
                                    BackendProjectTemplateBootstrapService backendTemplateBootstrapService,
                                    FullStackPortAllocator fullStackPortAllocator,
                                    TemplateSlotFillService slotFillService) {
        this.vueTemplateBootstrapService = vueTemplateBootstrapService;
        this.backendTemplateBootstrapService = backendTemplateBootstrapService;
        this.fullStackPortAllocator = fullStackPortAllocator;
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
            } else if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
                return executeFullStackInParallel(appId, userMessage, executor);
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

    private ParallelSlotFillResult executeFullStackInParallel(Long appId,
                                                              String userMessage,
                                                              ExecutorService executor) {
        try {
            FullStackGenerationContext fullStackContext = fullStackPortAllocator.allocate(appId);
            Path workspaceRoot = Path.of(fullStackContext.workspaceRoot());
            String frontendTemplateId = vueTemplateBootstrapService.selectTemplateId(userMessage);
            String backendTemplateId = "go-sqlite-backend-basic";

            CompletableFuture<?> frontendBootstrapFuture = CompletableFuture.supplyAsync(
                    () -> vueTemplateBootstrapService.bootstrapIfNecessary(workspaceRoot.resolve("frontend"), userMessage),
                    executor
            );
            CompletableFuture<?> backendBootstrapFuture = CompletableFuture.supplyAsync(
                    () -> backendTemplateBootstrapService.bootstrapIfNecessary(workspaceRoot.resolve("backend")),
                    executor
            );
            CompletableFuture<SlotFillResult> frontendSlotFuture = CompletableFuture.supplyAsync(
                    () -> slotFillService.fillSlots(frontendTemplateId, appId, userMessage),
                    executor
            );
            CompletableFuture<SlotFillResult> backendSlotFuture = CompletableFuture.supplyAsync(
                    () -> slotFillService.fillSlots(backendTemplateId, appId, userMessage),
                    executor
            );

            CompletableFuture.allOf(frontendBootstrapFuture, backendBootstrapFuture, frontendSlotFuture, backendSlotFuture).join();

            SlotFillResult frontendResult = frontendSlotFuture.get();
            SlotFillResult backendResult = backendSlotFuture.get();
            List<PatchOperation> operations = new ArrayList<>();
            List<String> filledSlots = new ArrayList<>();
            List<String> skippedSlots = new ArrayList<>();
            int totalChars = 0;
            if (frontendResult != null) {
                operations.addAll(prefixOperations("frontend/", frontendResult.patchOperations()));
                filledSlots.addAll(prefixSlotIds("frontend:", frontendResult.filledSlots()));
                skippedSlots.addAll(prefixSlotIds("frontend:", frontendResult.skippedSlots()));
                totalChars += frontendResult.totalChars();
            }
            if (backendResult != null) {
                operations.addAll(prefixOperations("backend/", backendResult.patchOperations()));
                filledSlots.addAll(prefixSlotIds("backend:", backendResult.filledSlots()));
                skippedSlots.addAll(prefixSlotIds("backend:", backendResult.skippedSlots()));
                totalChars += backendResult.totalChars();
            }
            if (operations.isEmpty()) {
                return new ParallelSlotFillResult(null, null, false);
            }
            SlotFillResult combined = new SlotFillResult(
                    frontendTemplateId + "+" + backendTemplateId,
                    filledSlots,
                    operations,
                    "全栈模板已复制，前后端 slots 已并行填充",
                    totalChars,
                    skippedSlots,
                    fullStackContext.toPayload()
            );
            return new ParallelSlotFillResult(null, combined, true);
        } catch (Exception e) {
            log.warn("全栈并行 Slot Fill 失败: {}", e.getMessage());
            return new ParallelSlotFillResult(null, null, false);
        }
    }

    private List<PatchOperation> prefixOperations(String prefix, List<PatchOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        return operations.stream()
                .map(operation -> new PatchOperation(
                        operation.action(),
                        prefix + operation.relativePath(),
                        operation.content(),
                        operation.oldContent(),
                        operation.newContent()
                ))
                .toList();
    }

    private List<String> prefixSlotIds(String prefix, List<String> slotIds) {
        if (slotIds == null || slotIds.isEmpty()) {
            return List.of();
        }
        return slotIds.stream().map(slotId -> prefix + slotId).toList();
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
