package kr.co.example.parallel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ========================================================================
 * [8] 병렬 처리 - CompletableFuture, parallelStream
 * ========================================================================
 *
 * ── 핵심 개념 ──
 *
 * 1. CompletableFuture
 *    - Java 8+ 비동기 프로그래밍의 핵심
 *    - 비동기 작업의 결과를 체이닝, 조합 가능
 *    - supplyAsync: 결과 반환 비동기 작업
 *    - runAsync: 결과 없는 비동기 작업
 *    - allOf: 모든 Future 완료 대기
 *    - anyOf: 하나라도 완료되면 반환
 *
 * 2. parallelStream
 *    - Java 8 Stream API의 병렬 처리
 *    - 내부적으로 ForkJoinPool 사용
 *    - 기본: ForkJoinPool.commonPool() (CPU 코어 수 - 1 스레드)
 *
 * 3. CompletableFuture vs parallelStream
 *    ┌───────────────────┬──────────────────────────────────────┐
 *    │ CompletableFuture  │ 스레드 풀 지정 가능                  │
 *    │                    │ 세밀한 에러 처리                     │
 *    │                    │ 비동기 체이닝/조합                   │
 *    │                    │ I/O 바운드에 적합                    │
 *    ├───────────────────┼──────────────────────────────────────┤
 *    │ parallelStream     │ 코드가 간결                          │
 *    │                    │ ForkJoinPool 공유 (제어 어려움)       │
 *    │                    │ CPU 바운드에 적합                     │
 *    │                    │ 블로킹 I/O 사용 시 성능 저하          │
 *    └───────────────────┴──────────────────────────────────────┘
 *
 * ── 사용 특성 ──
 *
 * 장점:
 * - 다수의 독립 작업을 동시 실행하여 총 소요 시간 단축
 * - CompletableFuture는 커스텀 Executor로 제어 가능
 * - AtomicInteger 등으로 스레드 안전한 결과 집계
 *
 * 주의점:
 * - parallelStream의 ForkJoinPool.commonPool()은 전역 공유
 *   → 하나의 작업이 오래 걸리면 다른 parallelStream에도 영향
 * - 병렬 처리 시 순서 보장 안 됨
 * - 공유 자원 접근 시 동기화 필요
 */
@Slf4j
@Service
public class ParallelProcessingService {

    private final ThreadPoolTaskExecutor batchExecutor;

    /**
     * @Qualifier로 특정 이름의 빈을 주입.
     * "batchExecutor"라는 이름의 ThreadPoolTaskExecutor 빈을 사용.
     */
    public ParallelProcessingService(
            @Qualifier("batchExecutor") ThreadPoolTaskExecutor batchExecutor) {
        this.batchExecutor = batchExecutor;
    }

    /**
     * CompletableFuture로 병렬 처리
     *
     * 배치 작업을 여러 청크로 분할하여 병렬 실행.
     * 각 청크를 CompletableFuture.supplyAsync()로 비동기 실행.
     * allOf().join()으로 모든 작업 완료를 기다림.
     *
     * ┌────────────────────────────────────────────────┐
     * │ 순차 처리: [Batch1] → [Batch2] → [Batch3]     │
     * │ 총 시간: 3초 + 3초 + 3초 = 9초                │
     * │                                                │
     * │ 병렬 처리: [Batch1]                             │
     * │            [Batch2]                             │
     * │            [Batch3]                             │
     * │ 총 시간: max(3초, 3초, 3초) = 3초               │
     * └────────────────────────────────────────────────┘
     */
    public int parallelBatchProcess(List<List<String>> batches) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 각 배치를 CompletableFuture로 비동기 실행
        List<CompletableFuture<int[]>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(
                        () -> processBatch(batch),  // 비동기 작업
                        batchExecutor               // 커스텀 Executor 사용
                ))
                .toList();

        // 모든 작업 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 결과 집계
        for (CompletableFuture<int[]> future : futures) {
            int[] result = future.join();  // 이미 완료됨, 블로킹 없음
            successCount.addAndGet(result[0]);
            failureCount.addAndGet(result[1]);
        }

        log.info("[Parallel] 완료 - success={}, failure={}",
                successCount.get(), failureCount.get());
        return successCount.get();
    }

    /**
     * parallelStream으로 간단한 병렬 변환
     *
     * 데이터 변환이 CPU 바운드이고 공유 상태가 없는 경우에 적합.
     *
     * 주의: ForkJoinPool.commonPool()을 사용하므로
     * 블로킹 I/O가 있으면 다른 parallelStream에 영향.
     */
    public List<String> parallelTransform(List<String> items) {
        return items.parallelStream()
                .map(item -> {
                    log.debug("[parallelStream] 변환 - item={}, thread={}",
                            item, Thread.currentThread().getName());
                    return item.toUpperCase() + "-PROCESSED";
                })
                .collect(Collectors.toList());
    }

    /**
     * CompletableFuture 체이닝 예제
     *
     * 여러 비동기 작업의 결과를 순차적으로 변환/조합.
     *
     * supplyAsync → thenApply → thenApply:
     * 비동기 실행 → 결과 변환1 → 결과 변환2
     *
     * thenCombine: 두 개의 Future 결과를 조합
     */
    public String chainedAsyncProcess(String input) {
        CompletableFuture<String> step1 = CompletableFuture.supplyAsync(
                () -> {
                    log.info("[Chain] Step 1: 데이터 조회 - input={}", input);
                    return "fetched-" + input;
                },
                batchExecutor
        );

        CompletableFuture<String> step2 = CompletableFuture.supplyAsync(
                () -> {
                    log.info("[Chain] Step 2: 외부 API 호출");
                    return "external-data";
                },
                batchExecutor
        );

        // 두 결과를 조합
        return step1
                .thenCombine(step2, (result1, result2) -> {
                    log.info("[Chain] 결과 조합: {} + {}", result1, result2);
                    return result1 + " | " + result2;
                })
                .thenApply(combined -> {
                    log.info("[Chain] 최종 변환: {}", combined);
                    return "FINAL: " + combined;
                })
                .join();  // 결과 대기
    }

    /**
     * 개별 배치 처리 로직
     *
     * @return int[]{successCount, failureCount}
     */
    private int[] processBatch(List<String> batch) {
        int success = 0, failure = 0;

        for (String item : batch) {
            try {
                log.debug("[Batch] 처리 - item={}, thread={}",
                        item, Thread.currentThread().getName());
                // 실제 처리 로직 (외부 API 호출 등)
                success++;
            } catch (Exception e) {
                failure++;
            }
        }

        return new int[]{success, failure};
    }
}
