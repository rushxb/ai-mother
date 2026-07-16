-- Route-level successful-duration history powers cached ETA/profile queries.
-- This is an analytics index only; it does not change task execution or recovery semantics.
SET @generation_task_route_duration_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'generation_task'
      AND index_name = 'idx_route_success_duration'
);
SET @generation_task_route_duration_index_sql = IF(
    @generation_task_route_duration_index_exists = 0,
    'ALTER TABLE generation_task ADD INDEX idx_route_success_duration (route, status, isDelete, endTime, id)',
    'SELECT 1'
);
PREPARE generation_task_route_duration_index_statement
    FROM @generation_task_route_duration_index_sql;
EXECUTE generation_task_route_duration_index_statement;
DEALLOCATE PREPARE generation_task_route_duration_index_statement;
