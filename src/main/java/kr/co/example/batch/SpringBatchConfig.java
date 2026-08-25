package kr.co.example.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * ========================================================================
 * Spring Batch 구성 예제
 * ========================================================================
 *
 * ── Spring Batch란? ──
 *
 * 대용량 데이터를 안정적으로 처리하기 위한 배치 프레임워크.
 * 재시작, 건너뛰기, 트랜잭션 관리, 메타데이터 기록 등을 자동으로 제공.
 *
 * ── 핵심 아키텍처 ──
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ JobLauncher                                                 │
 * │   └─ Job (하나의 배치 작업 단위)                               │
 * │       ├─ Step 1 (Tasklet 방식)                               │
 * │       │   └─ Tasklet: 단일 작업 실행 후 완료                   │
 * │       │                                                      │
 * │       ├─ Step 2 (Chunk 방식)                                 │
 * │       │   └─ [Reader] → [Processor] → [Writer]              │
 * │       │      N건 읽기    1건씩 변환     N건 묶어쓰기            │
 * │       │      ← ─ ─ ─ chunk 단위 반복 ─ ─ ─ →                │
 * │       │                                                      │
 * │       └─ Step 3 ...                                         │
 * │                                                              │
 * │ JobRepository (메타데이터 저장)                                │
 * │   → BATCH_JOB_INSTANCE: Job 정의 (이름 + 파라미터)            │
 * │   → BATCH_JOB_EXECUTION: 실행 이력 (시작/종료/상태)            │
 * │   → BATCH_STEP_EXECUTION: Step별 실행 이력 (읽기/쓰기/건너뛰기)│
 * └─────────────────────────────────────────────────────────────┘
 *
 * ── Tasklet vs Chunk 비교 ──
 *
 * ┌──────────────┬────────────────────────────┬──────────────────────────────┐
 * │ 항목          │ Tasklet                     │ Chunk                        │
 * ├──────────────┼────────────────────────────┼──────────────────────────────┤
 * │ 구조          │ 단일 execute() 메서드       │ Reader → Processor → Writer  │
 * │ 트랜잭션      │ execute() 전체가 1 TX       │ chunk 단위로 TX 커밋          │
 * │ 적합한 작업   │ 파일 삭제, 테이블 초기화     │ 대량 데이터 변환/이관          │
 * │ 재시작        │ 처음부터 재실행              │ 마지막 커밋 지점부터 재시작    │
 * │ 메모리        │ 전체 데이터 로딩 가능성      │ chunk 크기만큼만 메모리 사용   │
 * │ 코드 복잡도   │ 간단                        │ 3개 컴포넌트 구현 필요         │
 * └──────────────┴────────────────────────────┴──────────────────────────────┘
 *
 * ── Spring Boot 3.x 변경사항 ──
 *
 * Spring Batch 5.x (Boot 3.x):
 * - @EnableBatchProcessing 불필요 (자동 구성)
 * - JobBuilderFactory/StepBuilderFactory → JobBuilder/StepBuilder 직접 사용
 * - JobRepository, TransactionManager를 생성자로 직접 전달
 *
 * ── Chunk Size(청크 크기) 선택 가이드 ──
 *
 * ┌──────────────┬───────────────┬──────────────────────────────────┐
 * │ Chunk Size   │ 트랜잭션 빈도  │ 특성                              │
 * ├──────────────┼───────────────┼──────────────────────────────────┤
 * │ 작음 (10~50) │ 자주 커밋      │ 메모리 적게 사용, 실패 시 손실 적음 │
 * │              │               │ 커밋 오버헤드 큼, 처리 속도 느림    │
 * │ 중간 (100~500)│ 적절한 균형   │ 일반적으로 권장되는 범위            │
 * │ 큼 (1000+)   │ 드물게 커밋    │ 처리 속도 빠름, 메모리 많이 사용   │
 * │              │               │ 실패 시 롤백 범위 큼               │
 * └──────────────┴───────────────┴──────────────────────────────────┘
 *
 * 성능 최적화 기준:
 * - CPU 바운드 (변환 로직 무거움): chunk 크게 (500~1000)
 * - I/O 바운드 (DB/API 호출): chunk 적당히 (100~300)
 * - 메모리 제한 환경: chunk 작게 (50~100)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SpringBatchConfig {

    /** Spring Batch 메타데이터 저장소 (실행 이력, 상태 관리) */
    private final JobRepository jobRepository;

    /** Step 내 트랜잭션 관리 */
    private final PlatformTransactionManager transactionManager;

    // ================================================================
    // [1] Job 정의 - 여러 Step을 순차적으로 실행
    // ================================================================

    /**
     * 데이터 정리 Job 정의
     *
     * Job은 배치 작업의 최상위 단위.
     * 여러 Step을 순차적으로 실행하며, 실행 이력이 JobRepository에 기록됨.
     *
     * RunIdIncrementer:
     * - 같은 Job을 여러 번 실행 가능하도록 run.id 파라미터를 자동 증가
     * - 기본적으로 같은 파라미터의 Job은 재실행 불가 (이미 COMPLETED)
     * - 운영에서는 날짜 파라미터를 사용하여 일별 실행 구분
     *
     * Step 실행 순서:
     * 1. initStep: 테이블 초기화 (Tasklet)
     * 2. processStep: 데이터 변환 처리 (Chunk)
     *
     * 흐름 제어:
     * - start().next(): 순차 실행
     * - on("FAILED").to(): 조건부 분기
     * - on("*").end(): 기본 종료
     */
    @Bean
    public Job dataCleanupJob() {
        return new JobBuilder("dataCleanupJob", jobRepository)
                .incrementer(new RunIdIncrementer())     // 반복 실행 가능
                .start(initStep())                        // 1단계: 초기화
                .next(processStep())                      // 2단계: 데이터 처리
                .build();
    }

    // ================================================================
    // [2] Tasklet Step - 단일 작업 실행
    // ================================================================

    /**
     * 초기화 Step (Tasklet 방식)
     *
     * Tasklet: 하나의 execute() 메서드로 작업 수행.
     * → 테이블 TRUNCATE, 임시 파일 삭제, 상태 초기화 등 단순 작업에 적합.
     *
     * RepeatStatus:
     * - FINISHED: 작업 완료, Step 종료
     * - CONTINUABLE: 다시 execute() 호출 (반복 작업 시)
     */
    @Bean
    public Step initStep() {
        return new StepBuilder("initStep", jobRepository)
                .tasklet(initTasklet(), transactionManager)
                .build();
    }

    /**
     * 초기화 Tasklet 구현
     *
     * @StepScope:
     * - Step 실행 시점에 빈 생성 (Lazy Initialization)
     * - Job Parameter를 주입받을 수 있음
     * - Step 종료 시 빈 소멸 → 메모리 해제
     *
     * 활용 예: #{jobParameters['targetDate']} 로 실행 날짜 주입
     */
    @Bean
    @StepScope
    public Tasklet initTasklet() {
        return (contribution, chunkContext) -> {
            log.info("[Batch] 초기화 Tasklet 실행 - 임시 데이터 정리");

            // 실무 예시:
            // jdbcTemplate.execute("TRUNCATE TABLE temp_processing");
            // fileSystemService.deleteOldFiles(targetDate);

            return RepeatStatus.FINISHED;  // 1회 실행 후 종료
        };
    }

    // ================================================================
    // [3] Chunk Step - Reader → Processor → Writer
    // ================================================================

    /**
     * 데이터 처리 Step (Chunk 방식)
     *
     * Chunk 처리 흐름:
     * ┌──────────────────────────────────────────────────────┐
     * │ chunk(100) 설정 시:                                   │
     * │                                                       │
     * │ [Reader]  → 1건 읽기 × 100회 = 100건 수집             │
     * │     ↓                                                 │
     * │ [Processor] → 1건씩 변환 × 100회                      │
     * │     ↓                                                 │
     * │ [Writer] → 100건 일괄 쓰기 (1회 호출)                  │
     * │     ↓                                                 │
     * │ TX COMMIT (100건 단위로 커밋)                           │
     * │     ↓                                                 │
     * │ Reader가 null 반환할 때까지 반복                        │
     * └──────────────────────────────────────────────────────┘
     *
     * <String, String>:
     * - 첫 번째 타입: Reader가 읽는 입력 타입
     * - 두 번째 타입: Writer가 쓰는 출력 타입
     *
     * chunk(100):
     * - 100건 단위로 트랜잭션 커밋
     * - Reader가 100건 읽으면 Processor → Writer 실행
     * - 중간에 실패 시 해당 chunk만 롤백 (이전 chunk는 이미 커밋)
     *
     * faultTolerant():
     * - skip, retry 등 장애 허용 정책 활성화
     *
     * skip(Exception.class).skipLimit(10):
     * - 특정 예외 발생 시 해당 아이템만 건너뛰고 계속 진행
     * - 최대 10건까지 건너뛰기 허용 (초과 시 Step 실패)
     *
     * retry(Exception.class).retryLimit(3):
     * - 일시적 오류 시 해당 아이템을 3회까지 재시도
     */
    @Bean
    public Step processStep() {
        return new StepBuilder("processStep", jobRepository)
                .<String, String>chunk(100, transactionManager)  // 100건 단위 커밋
                .reader(itemReader())
                .processor(itemProcessor())
                .writer(itemWriter())
                .faultTolerant()                         // 장애 허용 모드
                .skip(Exception.class)                   // 예외 발생 시 건너뛰기
                .skipLimit(10)                           // 최대 10건 건너뛰기
                .retry(Exception.class)                  // 예외 시 재시도
                .retryLimit(3)                           // 최대 3회 재시도
                .build();
    }

    /**
     * ItemReader - 데이터 읽기
     *
     * Reader 구현체 종류:
     * ┌──────────────────────────┬──────────────────────────────────────┐
     * │ 구현체                    │ 설명                                 │
     * ├──────────────────────────┼──────────────────────────────────────┤
     * │ JdbcCursorItemReader     │ DB 커서로 1건씩 읽기 (메모리 효율적)   │
     * │                          │ 대용량에 적합, ResultSet 유지          │
     * │ JdbcPagingItemReader     │ 페이지 단위 쿼리 (LIMIT/OFFSET)      │
     * │                          │ 커서보다 안전, 커넥션 짧게 사용         │
     * │ JpaPagingItemReader      │ JPA 기반 페이징                       │
     * │ FlatFileItemReader       │ CSV/TSV 파일 읽기                     │
     * │ ListItemReader           │ 메모리 리스트 읽기 (테스트용)           │
     * └──────────────────────────┴──────────────────────────────────────┘
     *
     * ── Cursor vs Paging 비교 ──
     *
     * ┌────────────┬────────────────────────────┬────────────────────────────┐
     * │ 항목        │ Cursor                      │ Paging                      │
     * ├────────────┼────────────────────────────┼────────────────────────────┤
     * │ DB 커넥션   │ Step 동안 유지 (장시간 점유) │ 페이지마다 새 쿼리 (짧게 사용)│
     * │ 메모리      │ 1건씩 fetch (효율적)        │ 페이지 크기만큼 로딩          │
     * │ 정렬        │ 불필요                      │ ORDER BY 필수 (페이지 일관성) │
     * │ 대용량      │ 적합 (수천만 건)             │ OFFSET 커지면 성능 저하       │
     * │ 장애 복구   │ 재시작 시 커서 재생성 필요    │ 페이지 번호로 재시작 용이      │
     * └────────────┴────────────────────────────┴────────────────────────────┘
     */
    @Bean
    public ItemReader<String> itemReader() {
        // 예시용 ListItemReader (실무에서는 JdbcCursorItemReader 등 사용)
        return new ListItemReader<>(List.of("data1", "data2", "data3"));
    }

    /**
     * ItemProcessor - 데이터 변환/필터링
     *
     * 역할:
     * - 입력 데이터를 출력 데이터로 변환 (타입 변경 가능)
     * - null 반환 시 해당 아이템은 Writer에 전달되지 않음 (필터링)
     * - 비즈니스 검증 로직 수행
     *
     * 성능 고려:
     * - Processor에서 외부 API 호출 시 chunk 크기를 작게 설정
     * - CPU 바운드 변환만 있으면 chunk 크기를 크게 설정
     * - Processor는 1건씩 호출되므로 N+1 쿼리 주의
     */
    @Bean
    public ItemProcessor<String, String> itemProcessor() {
        return item -> {
            log.debug("[Batch] Processor - 변환: {}", item);

            // null 반환 시 해당 아이템 필터링 (Writer에 전달 안 됨)
            if (item.contains("skip")) {
                return null;
            }

            return "PROCESSED_" + item.toUpperCase();
        };
    }

    /**
     * ItemWriter - 데이터 일괄 쓰기
     *
     * chunk 크기만큼 모인 데이터가 한 번에 전달됨.
     * 이 시점에 JDBC 배치 INSERT나 파일 쓰기를 수행.
     *
     * Writer 구현체 종류:
     * - JdbcBatchItemWriter: JDBC 배치 INSERT (가장 빠름)
     * - JpaItemWriter: JPA persist/merge
     * - FlatFileItemWriter: CSV/TSV 파일 쓰기
     * - CompositeItemWriter: 여러 Writer를 순차 실행
     *
     * 성능 고려:
     * - JdbcBatchItemWriter가 JpaItemWriter보다 2~5배 빠름
     *   (Hibernate 영속성 컨텍스트 오버헤드 없음)
     * - 대용량 쓰기 시 JdbcBatchItemWriter + rewriteBatchedStatements=true 조합 권장
     */
    @Bean
    public ItemWriter<String> itemWriter() {
        return items -> {
            log.info("[Batch] Writer - {}건 일괄 저장", items.size());
            for (String item : items) {
                log.debug("[Batch] Writer - 저장: {}", item);
                // 실무: jdbcTemplate.batchUpdate() 또는 repository.saveAll()
            }
        };
    }
}
