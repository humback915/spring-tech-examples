package kr.co.example.parallel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ========================================================================
 * CompletableFuture 개념 & 활용 예제
 * ========================================================================
 *
 * ── CompletableFuture란? ──
 *
 * Java 8에서 도입된 비동기 프로그래밍의 핵심 클래스.
 * 기존 Future의 한계(결과 조합 불가, 콜백 없음)를 해결.
 *
 * ── Future vs CompletableFuture ──
 *
 * ┌────────────────────┬──────────────────────────────────────────┐
 * │ Future (Java 5)     │ get()으로만 결과 조회 (블로킹)            │
 * │                     │ 여러 Future 결과 조합 불가               │
 * │                     │ 콜백(완료 후 자동 실행) 미지원            │
 * │                     │ 예외 처리가 불편 (ExecutionException)     │
 * ├────────────────────┼──────────────────────────────────────────┤
 * │ CompletableFuture   │ 논블로킹 콜백 체이닝 가능                │
 * │ (Java 8)            │ 여러 Future 결과 조합 (thenCombine 등)   │
 * │                     │ 예외 처리 체이닝 (exceptionally, handle) │
 * │                     │ 커스텀 Executor 지정 가능                │
 * │                     │ 직접 결과 완료 가능 (complete())          │
 * └────────────────────┴──────────────────────────────────────────┘
 *
 * ── 생성 방법 ──
 *
 * 1. supplyAsync(Supplier, Executor)
 *    - 결과를 반환하는 비동기 작업
 *    - CompletableFuture<String> f = supplyAsync(() -> "result", executor);
 *
 * 2. runAsync(Runnable, Executor)
 *    - 결과 없는 비동기 작업
 *    - CompletableFuture<Void> f = runAsync(() -> doWork(), executor);
 *
 * 3. completedFuture(T value)
 *    - 이미 완료된 Future 생성 (테스트, @Async 반환용)
 *    - CompletableFuture<String> f = completedFuture("done");
 *
 * ── 체이닝 메서드 분류 ──
 *
 * ┌────────────────────┬─────────────────────────────────────────────┐
 * │ 변환 (Transform)    │                                             │
 * │   thenApply(fn)     │ 결과를 변환하여 새 값 반환 (map과 유사)     │
 * │   thenApplyAsync    │ 변환을 다른 스레드에서 비동기 실행           │
 * ├────────────────────┼─────────────────────────────────────────────┤
 * │ 소비 (Consume)      │                                             │
 * │   thenAccept(fn)    │ 결과를 소비 (반환값 없음)                   │
 * │   thenAcceptAsync   │ 소비를 다른 스레드에서 비동기 실행           │
 * ├────────────────────┼─────────────────────────────────────────────┤
 * │ 실행 (Run)          │                                             │
 * │   thenRun(runnable) │ 결과와 무관하게 다음 작업 실행               │
 * │   thenRunAsync      │ 다음 작업을 비동기 실행                     │
 * ├────────────────────┼─────────────────────────────────────────────┤
 * │ 조합 (Combine)      │                                             │
 * │   thenCombine       │ 두 Future의 결과를 조합하여 새 값 생성      │
 * │   thenCompose       │ Future의 결과로 다음 Future를 생성 (flatMap)│
 * │   allOf             │ 모든 Future가 완료될 때까지 대기             │
 * │   anyOf             │ 하나라도 완료되면 즉시 반환                  │
 * ├────────────────────┼─────────────────────────────────────────────┤
 * │ 예외 처리           │                                             │
 * │   exceptionally     │ 예외 발생 시 대체 값 반환                   │
 * │   handle            │ 성공/실패 모두 처리 (try-catch와 유사)      │
 * │   whenComplete      │ 성공/실패 시 사이드 이펙트 실행 (로깅 등)   │
 * └────────────────────┴─────────────────────────────────────────────┘
 *
 * ── Executor 지정의 중요성 ──
 *
 * supplyAsync, runAsync에 Executor를 지정하지 않으면
 * ForkJoinPool.commonPool()을 사용함.
 *
 * commonPool의 문제:
 * - 전역 공유 → 다른 parallelStream, 다른 CompletableFuture와 공유
 * - 기본 크기 = CPU 코어 수 - 1 → I/O 바운드 작업에 부족
 * - 하나의 작업이 블로킹하면 전체 commonPool이 영향받음
 *
 * 해결: 용도별 커스텀 Executor(ThreadPoolTaskExecutor)를 지정하여 격리
 */
@Slf4j
@Service
public class CompletableFutureExample {

    private final ThreadPoolTaskExecutor batchExecutor;

    public CompletableFutureExample(
            @Qualifier("batchExecutor") ThreadPoolTaskExecutor batchExecutor) {
        this.batchExecutor = batchExecutor;
    }

    // ================================================================
    // [1] 기본 생성 및 결과 조회
    // ================================================================

    /**
     * supplyAsync: 결과를 반환하는 비동기 작업 생성
     *
     * 동작:
     * 1. batchExecutor 스레드 풀에서 Supplier 비동기 실행
     * 2. 결과가 준비되면 CompletableFuture에 저장
     * 3. join()으로 블로킹 대기하여 결과 반환
     *
     * join() vs get():
     * - join(): unchecked 예외(CompletionException)를 던짐
     * - get(): checked 예외(ExecutionException, InterruptedException)를 던짐
     * - join()이 코드가 더 간결 (try-catch 불필요)
     */
    public String basicSupplyAsync() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> {
                    log.info("[supplyAsync] 비동기 작업 실행 - thread={}",
                            Thread.currentThread().getName());
                    return "async-result";
                },
                batchExecutor  // 커스텀 Executor 지정 (미지정 시 commonPool 사용)
        );

        // join(): 결과가 준비될 때까지 블로킹 대기
        String result = future.join();
        log.info("[supplyAsync] 결과: {}", result);
        return result;
    }

    /**
     * get() with timeout: 시간 제한 결과 조회
     *
     * 지정된 시간 내에 결과가 준비되지 않으면 TimeoutException 발생.
     * 외부 API 호출 등 무한 대기를 방지할 때 사용.
     */
    public String getWithTimeout() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> {
                    // 오래 걸리는 작업 시뮬레이션
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    return "timeout-result";
                },
                batchExecutor
        );

        try {
            // 최대 3초 대기 → 3초 초과 시 TimeoutException
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("[get] 타임아웃 - 3초 초과");
            return "TIMEOUT";
        } catch (InterruptedException | ExecutionException e) {
            log.error("[get] 실행 오류", e);
            return "ERROR";
        }
    }

    // ================================================================
    // [2] 체이닝: 변환 → 소비 → 실행
    // ================================================================

    /**
     * thenApply: 결과 변환 (Stream의 map과 유사)
     *
     * 첫 번째 비동기 작업의 결과를 받아 변환.
     * 같은 스레드 또는 완료 스레드에서 실행.
     *
     * 체이닝 흐름:
     * supplyAsync("data") → thenApply(uppercase) → thenApply(prefix 추가)
     */
    public String chainingTransform() {
        String result = CompletableFuture
                .supplyAsync(() -> "hello world", batchExecutor)
                // thenApply: 이전 결과를 변환하여 새 값 반환
                .thenApply(s -> {
                    log.info("[thenApply-1] 대문자 변환");
                    return s.toUpperCase();
                })
                // 두 번째 thenApply: 이전 변환 결과에 추가 가공
                .thenApply(s -> {
                    log.info("[thenApply-2] 접두사 추가");
                    return "RESULT: " + s;
                })
                .join();

        log.info("[chaining] 최종 결과: {}", result);
        return result; // "RESULT: HELLO WORLD"
    }

    /**
     * thenAccept + thenRun: 소비와 후처리
     *
     * thenAccept: 결과를 받아서 소비 (반환값 없음) → 로깅, 저장 등
     * thenRun: 이전 결과와 무관하게 다음 작업 실행 → 정리 작업, 알림 등
     */
    public void chainingConsumeAndRun() {
        CompletableFuture
                .supplyAsync(() -> "processed-data", batchExecutor)
                // thenAccept: 결과를 받아 소비 (반환 없음)
                .thenAccept(result -> {
                    log.info("[thenAccept] 결과 저장: {}", result);
                    // repository.save(result);
                })
                // thenRun: 이전 결과와 무관하게 실행
                .thenRun(() -> {
                    log.info("[thenRun] 후처리 완료 알림");
                    // notificationService.send("처리 완료");
                })
                .join();
    }

    // ================================================================
    // [3] 조합: 여러 Future 결과 합치기
    // ================================================================

    /**
     * thenCombine: 두 개의 독립 Future 결과를 조합
     *
     * 두 작업이 병렬로 실행된 후, 양쪽 결과를 하나로 합침.
     *
     * 흐름:
     * ┌─ future1: 사용자 조회 ──────┐
     * │                              ├─ 두 결과 조합 → "user + orders"
     * └─ future2: 주문 목록 조회 ───┘
     *
     * 순차 실행이었다면: 2초 + 2초 = 4초
     * 병렬 실행 후 조합: max(2초, 2초) = 2초
     */
    public String combineTwoFutures() {
        // 작업 1: 사용자 정보 조회
        CompletableFuture<String> userFuture = CompletableFuture.supplyAsync(
                () -> {
                    log.info("[combine] 사용자 조회 시작");
                    return "User-홍길동";
                },
                batchExecutor
        );

        // 작업 2: 주문 목록 조회 (작업 1과 병렬 실행)
        CompletableFuture<String> ordersFuture = CompletableFuture.supplyAsync(
                () -> {
                    log.info("[combine] 주문 목록 조회 시작");
                    return "Orders-3건";
                },
                batchExecutor
        );

        // thenCombine: 두 결과를 하나로 합침
        String result = userFuture
                .thenCombine(ordersFuture, (user, orders) -> {
                    log.info("[combine] 결과 조합: {} + {}", user, orders);
                    return user + " | " + orders;
                })
                .join();

        log.info("[combine] 최종: {}", result);
        return result; // "User-홍길동 | Orders-3건"
    }

    /**
     * thenCompose: Future의 결과로 다음 Future 생성 (flatMap과 유사)
     *
     * thenApply vs thenCompose:
     * - thenApply(fn) → fn이 일반 값 반환 → CompletableFuture<T>
     * - thenCompose(fn) → fn이 CompletableFuture 반환 → 자동 풀어냄 (flatten)
     *
     * 사용 시나리오:
     * 첫 번째 결과(userId)로 두 번째 비동기 작업(주문 조회)을 수행해야 할 때.
     * 즉, 작업 간에 의존성이 있는 순차 비동기 처리.
     */
    public String sequentialAsync() {
        String result = CompletableFuture
                .supplyAsync(() -> {
                    log.info("[compose] Step 1: 사용자 ID 조회");
                    return 42L; // userId
                }, batchExecutor)
                // thenCompose: 이전 결과(userId)로 새로운 비동기 작업 실행
                .thenCompose(userId -> CompletableFuture.supplyAsync(() -> {
                    log.info("[compose] Step 2: userId={}의 주문 조회", userId);
                    return "Order-for-" + userId;
                }, batchExecutor))
                .join();

        log.info("[compose] 최종: {}", result);
        return result;
    }

    /**
     * allOf: 모든 Future가 완료될 때까지 대기
     *
     * 여러 독립 작업을 병렬로 실행하고, 모두 완료된 후 결과를 집계.
     *
     * allOf()는 CompletableFuture<Void>를 반환하므로
     * 각 Future의 결과는 개별적으로 join()으로 조회.
     *
     * 흐름:
     * ┌─ task1 (2초) ─┐
     * │─ task2 (1초) ─├─ allOf → 모두 완료 (2초) → 결과 집계
     * └─ task3 (3초) ─┘   총 시간: max(2,1,3) = 3초 (순차면 6초)
     */
    public List<String> waitForAll() {
        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(
                () -> "result-A", batchExecutor);
        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(
                () -> "result-B", batchExecutor);
        CompletableFuture<String> f3 = CompletableFuture.supplyAsync(
                () -> "result-C", batchExecutor);

        // 모든 Future 완료 대기
        CompletableFuture.allOf(f1, f2, f3).join();

        // 개별 결과 수집 (이미 완료되었으므로 join()은 즉시 반환)
        List<String> results = List.of(f1.join(), f2.join(), f3.join());
        log.info("[allOf] 모든 결과: {}", results);
        return results;
    }

    // ================================================================
    // [4] 예외 처리
    // ================================================================

    /**
     * exceptionally: 예외 발생 시 대체 값 반환
     *
     * 체이닝 중 예외가 발생하면 대체 값으로 복구.
     * try-catch의 catch 블록과 유사.
     *
     * 예외가 없으면 → exceptionally 무시, 정상 결과 반환
     * 예외가 있으면 → exceptionally 실행, 대체 값 반환
     */
    public String exceptionHandling() {
        String result = CompletableFuture
                .supplyAsync(() -> {
                    if (true) throw new RuntimeException("외부 API 연결 실패");
                    return "success";
                }, batchExecutor)
                // exceptionally: 예외 발생 시 대체 값 반환
                .exceptionally(ex -> {
                    log.error("[exceptionally] 예외 복구 - error={}", ex.getMessage());
                    return "FALLBACK-VALUE";
                })
                .join();

        log.info("[exception] 결과: {}", result); // "FALLBACK-VALUE"
        return result;
    }

    /**
     * handle: 성공/실패 모두 처리
     *
     * exceptionally는 예외만 처리하지만,
     * handle은 성공 결과와 예외를 동시에 받아 처리.
     *
     * 파라미터:
     * - result: 성공 시 결과 (실패 시 null)
     * - ex: 실패 시 예외 (성공 시 null)
     *
     * try-catch-finally와 유사하지만 함수형으로 작성.
     */
    public String handleBoth() {
        String result = CompletableFuture
                .supplyAsync(() -> {
                    // 성공/실패 시나리오
                    return "success-data";
                }, batchExecutor)
                // handle: 성공과 실패를 모두 처리
                .handle((data, ex) -> {
                    if (ex != null) {
                        log.error("[handle] 실패 - error={}", ex.getMessage());
                        return "ERROR: " + ex.getMessage();
                    }
                    log.info("[handle] 성공 - data={}", data);
                    return "OK: " + data;
                })
                .join();

        log.info("[handle] 결과: {}", result);
        return result;
    }

    /**
     * whenComplete: 성공/실패 시 사이드 이펙트 (결과 변경 불가)
     *
     * handle과 달리 결과를 변경할 수 없음.
     * 로깅, 메트릭 수집, 알림 등 사이드 이펙트에 적합.
     *
     * handle → 결과를 변환하여 반환 (map)
     * whenComplete → 결과를 보고만 함 (peek)
     */
    public String sideEffectOnComplete() {
        String result = CompletableFuture
                .supplyAsync(() -> "data", batchExecutor)
                // whenComplete: 결과를 변경하지 않고 사이드 이펙트만 실행
                .whenComplete((data, ex) -> {
                    if (ex != null) {
                        log.error("[whenComplete] 실패 로깅 - error={}", ex.getMessage());
                        // 메트릭 카운터 증가, 알림 발송 등
                    } else {
                        log.info("[whenComplete] 성공 로깅 - data={}", data);
                        // 메트릭 카운터 증가
                    }
                })
                .join();

        return result; // 원래 결과 "data"가 그대로 반환됨
    }
}
