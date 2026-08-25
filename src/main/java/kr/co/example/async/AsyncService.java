package kr.co.example.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * ========================================================================
 * @Async 사용 예제
 * ========================================================================
 *
 * ── @Async 사용 패턴 ──
 *
 * 1. Fire-and-Forget (void 반환)
 *    - 호출자가 결과를 기다리지 않고 즉시 반환
 *    - 알림 발송, 로그 저장, 이벤트 발행 등 사이드 이펙트에 적합
 *    - 예외 발생 시 AsyncUncaughtExceptionHandler에서 처리 (호출자에게 전파 안 됨)
 *
 * 2. CompletableFuture<T> 반환
 *    - 비동기 결과를 나중에 조회/조합 가능
 *    - thenApply, thenCombine, allOf 등으로 체이닝
 *    - 예외는 future.get() 또는 join() 시 호출자에게 전파
 *
 * ── @Async("executorName") 동작 ──
 *
 * executor 이름을 지정하면 해당 이름의 빈(ThreadPoolTaskExecutor)을 사용.
 * 미지정 시 AsyncConfigurer.getAsyncExecutor()의 기본 Executor 사용.
 *
 * 용도별 Executor를 분리하는 이유:
 * - 장애 격리: 한 종류의 작업이 풀을 점유해도 다른 작업에 영향 없음
 * - 모니터링: 스레드 이름 접두사로 로그에서 작업 종류를 쉽게 구분
 * - 튜닝: 각 작업의 특성(CPU 바운드/I/O 바운드)에 맞게 풀 사이즈 조절
 *
 * ── 주의사항 ──
 *
 * 1. self-invocation 문제
 *    같은 클래스 내부에서 @Async 메서드를 호출하면 프록시를 거치지 않아 동기로 실행됨.
 *    반드시 외부 빈에서 호출해야 비동기로 동작.
 *
 *    잘못된 예:
 *      public void caller() { this.sendNotification(1L, "msg"); } // 동기 실행!
 *    올바른 예:
 *      asyncService.sendNotification(1L, "msg"); // 외부 빈에서 호출 → 비동기 실행
 *
 * 2. 예외 처리 차이
 *    - void 반환: 예외가 호출자에게 전파되지 않음 → AsyncUncaughtExceptionHandler에서만 처리
 *    - CompletableFuture 반환: future.get()/join() 시 ExecutionException으로 감싸져 전파
 *
 * 3. 트랜잭션 분리
 *    @Async 메서드는 호출자의 트랜잭션에 참여하지 않음.
 *    비동기 메서드 내에서 DB 작업이 필요하면 별도 @Transactional 선언 필요.
 */
@Slf4j
@Service
public class AsyncService {

    /**
     * Fire-and-Forget 비동기 메서드 (void 반환)
     *
     * "taskExecutor" 빈의 스레드 풀에서 실행.
     * (ThreadPoolConfig에서 정의: core=10, max=50, queue=100)
     *
     * 호출자는 이 메서드를 호출한 즉시 반환됨.
     * 메서드 내부 로직은 "async-" 접두사의 별도 스레드에서 실행.
     *
     * 예외 발생 시:
     * - 호출자에게 전파되지 않음 (void 반환이므로)
     * - AsyncConfig의 AsyncUncaughtExceptionHandler에서 처리
     * - 로깅, 모니터링 알림 등 수행
     *
     * @param userId  알림 수신 대상 사용자 ID
     * @param message 알림 내용
     */
    @Async("taskExecutor")
    public void sendNotification(Long userId, String message) {
        log.info("[Async] 알림 발송 시작 - userId={}, thread={}",
                userId, Thread.currentThread().getName());

        // 외부 API 호출 시뮬레이션 (FCM 푸시, SMS 발송 등)
        // 실무에서는 WebClient나 RestTemplate으로 외부 서비스 호출
        try {
            Thread.sleep(2000); // 2초 소요되는 외부 API 호출 시뮬레이션
        } catch (InterruptedException e) {
            // interrupt 복원: 상위 스레드 풀이 인터럽트 상태를 올바르게 감지하도록
            Thread.currentThread().interrupt();
        }

        log.info("[Async] 알림 발송 완료 - userId={}", userId);
    }

    /**
     * CompletableFuture 반환 비동기 메서드
     *
     * "taskExecutor" 빈의 스레드 풀에서 실행.
     *
     * 호출자가 나중에 결과를 조회하거나 여러 Future를 조합할 수 있음.
     *
     * 사용 예 1: 결과 대기
     *   CompletableFuture<String> future = asyncService.processData("input");
     *   // 다른 작업 수행...
     *   String result = future.get(); // 결과가 준비될 때까지 블로킹 대기
     *
     * 사용 예 2: 체이닝 (논블로킹)
     *   asyncService.processData("input")
     *       .thenApply(result -> transform(result))     // 결과 변환
     *       .thenAccept(transformed -> save(transformed)); // 최종 소비
     *
     * 사용 예 3: 여러 Future 조합
     *   CompletableFuture<String> f1 = asyncService.processData("A");
     *   CompletableFuture<String> f2 = asyncService.processData("B");
     *   CompletableFuture.allOf(f1, f2).join(); // 둘 다 완료될 때까지 대기
     *
     * CompletableFuture.completedFuture()를 사용하는 이유:
     * @Async가 메서드를 별도 스레드에서 실행한 후 결과를 이미 완료된 Future로 감싸 반환.
     * 이중으로 비동기 실행되지 않음 (Async가 스레드를 관리, completedFuture는 결과 포장용).
     *
     * @param input 처리할 입력 데이터
     * @return 비동기 처리 결과를 담은 CompletableFuture
     */
    @Async("taskExecutor")
    public CompletableFuture<String> processData(String input) {
        log.info("[Async] 데이터 처리 시작 - input={}, thread={}",
                input, Thread.currentThread().getName());

        // 비동기 처리 로직 (DB 조회, 외부 API 호출, 변환 등)
        String result = "processed-" + input;

        log.info("[Async] 데이터 처리 완료 - result={}", result);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 다른 Executor를 사용하는 비동기 메서드
     *
     * "dispatchExecutor" 빈의 스레드 풀에서 실행.
     * (ThreadPoolConfig에서 정의: core=2, max=4, queue=5)
     *
     * "taskExecutor"(core=10)보다 작은 풀을 사용하는 이유:
     * - 이벤트 디스패치는 가벼운 작업 (Kafka 발행, 웹훅 트리거 등)
     * - 대규모 풀이 불필요하며, 리소스를 다른 작업에 양보
     * - "dispatch-" 접두사로 로그에서 쉽게 구분
     *
     * @param eventType 이벤트 타입 (ORDER_CREATED, PAYMENT_COMPLETED 등)
     * @param payload   이벤트 페이로드 (JSON 문자열)
     */
    @Async("dispatchExecutor")
    public void dispatchEvent(String eventType, String payload) {
        log.info("[Async] 이벤트 디스패치 - type={}, thread={}",
                eventType, Thread.currentThread().getName());
        // Kafka 발행, 웹훅 호출, 내부 이벤트 발행 등
    }
}
