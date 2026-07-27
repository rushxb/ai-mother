package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 用于持久生成关键路径跨度的显式 SQL 映射器。 */
public interface GenerationTaskSpanMapper {

    @Insert("""
            INSERT INTO generation_task_span (
                spanId, taskId, stage, category, status,
                startedAt, endedAt, durationMs, detail, createTime, isDelete
            ) VALUES (
                #{spanId}, #{taskId}, #{stage}, #{category}, #{status},
                #{startedAt}, #{endedAt}, #{durationMs}, #{detail}, #{createTime}, 0
            )
            ON DUPLICATE KEY UPDATE spanId = spanId
            """)
    int insertSpan(GenerationTaskSpan span);

    @Select("""
            SELECT id, spanId, taskId, stage, category, status,
                   startedAt, endedAt, durationMs, detail, createTime, isDelete
            FROM generation_task_span
            WHERE taskId = #{taskId}
              AND isDelete = 0
            ORDER BY startedAt ASC, id ASC
            LIMIT #{limit}
            """)
    List<GenerationTaskSpan> selectByTaskId(@Param("taskId") String taskId,
                                             @Param("limit") int limit);
    @Select("""
            SELECT s.stage, s.category, s.durationMs
            FROM generation_task_span s
            INNER JOIN generation_task t
                    ON t.taskId = s.taskId
                   AND t.isDelete = 0
            WHERE t.route = #{route}
              AND t.status = 'success'
              AND s.status = 'success'
              AND s.durationMs > 0
              AND s.isDelete = 0
            ORDER BY t.endTime DESC, s.startedAt DESC, s.id DESC
            LIMIT #{limit}
            """)
    List<GenerationTaskSpan> selectRecentSuccessfulByRoute(@Param("route") String route,
                                                            @Param("limit") int limit);

}
