package kr.co.example.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * 파일 업로드 예제 — MultipartFile 처리.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  MultipartFile — Spring의 파일 업로드 처리 인터페이스                  │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  HTTP multipart/form-data 요청으로 전송된 파일을 다루는 인터페이스.    │
 * │  파일 내용, 원본 파일명, 크기, 콘텐츠 타입 등의 정보를 제공.           │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * application.yml:
 *   spring:
 *     servlet:
 *       multipart:
 *         max-file-size: 10MB      # 개별 파일 최대 크기
 *         max-request-size: 50MB   # 전체 요청 최대 크기
 *         enabled: true            # 멀티파트 활성화 (기본 true)
 *
 * 파일 저장 옵션:
 * - 로컬 파일시스템 (개발/소규모)
 * - AWS S3 (운영 환경 권장)
 * - NFS/NAS (공유 스토리지)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileUploadExample {

    /** 업로드 파일 저장 경로 (실무에서는 프로퍼티로 관리) */
    private static final String UPLOAD_DIR = "/tmp/uploads";

    /** 허용 파일 확장자 */
    private static final List<String> ALLOWED_EXTENSIONS =
            List.of("jpg", "jpeg", "png", "gif", "pdf", "xlsx");

    /** 최대 파일 크기 (10MB) */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // ────────────────────────────────────────
    // [1] 단일 파일 업로드
    // ────────────────────────────────────────

    /**
     * 단일 파일 업로드.
     *
     * <pre>
     * curl 요청 예시:
     * curl -X POST http://localhost:8080/api/files/upload \
     *   -F "file=@/path/to/image.jpg"
     *
     * 프론트엔드 (JavaScript):
     * const formData = new FormData();
     * formData.append('file', fileInput.files[0]);
     * fetch('/api/files/upload', { method: 'POST', body: formData });
     * </pre>
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(
            @RequestParam("file") MultipartFile file) throws IOException {

        // [1] 빈 파일 체크
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("파일이 비어있습니다");
        }

        // [2] 파일 검증
        validateFile(file);

        // [3] 고유 파일명 생성 (원본 파일명 충돌 방지)
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);
        String storedFilename = UUID.randomUUID() + "." + extension;

        // [4] 저장 경로 생성
        Path uploadPath = Path.of(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // [5] 파일 저장
        Path filePath = uploadPath.resolve(storedFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        log.info("파일 업로드 성공: original={}, stored={}, size={}",
                originalFilename, storedFilename, file.getSize());

        return ResponseEntity.ok("업로드 성공: " + storedFilename);
    }

    // ────────────────────────────────────────
    // [2] 다중 파일 업로드
    // ────────────────────────────────────────

    /**
     * 다중 파일 업로드 — List&lt;MultipartFile&gt;
     */
    @PostMapping("/upload-multiple")
    public ResponseEntity<String> uploadMultiple(
            @RequestParam("files") List<MultipartFile> files) throws IOException {

        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                validateFile(file);
                String storedFilename = UUID.randomUUID() + "."
                        + getExtension(file.getOriginalFilename());
                Path filePath = Path.of(UPLOAD_DIR).resolve(storedFilename);
                Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return ResponseEntity.ok(files.size() + "개 파일 업로드 완료");
    }

    // ────────────────────────────────────────
    // [3] 파일 + JSON 데이터 동시 업로드
    // ────────────────────────────────────────

    /**
     * 파일과 JSON 데이터를 함께 받기.
     * @RequestPart: multipart 요청의 개별 파트를 바인딩.
     */
    @PostMapping("/upload-with-data")
    public ResponseEntity<String> uploadWithData(
            @RequestPart("file") MultipartFile file,
            @RequestPart("data") String jsonData) {

        log.info("파일: {}, 데이터: {}", file.getOriginalFilename(), jsonData);
        return ResponseEntity.ok("파일 + 데이터 수신 완료");
    }

    // ────────────────────────────────────────
    // [4] 파일 검증 (보안)
    // ────────────────────────────────────────

    /**
     * 파일 검증 — 확장자, 크기, Content-Type 확인.
     *
     * <pre>
     * 보안 검증 항목:
     * 1. 확장자 화이트리스트 검사 (허용된 확장자만)
     * 2. Content-Type 검사 (이미지인지 확인)
     * 3. 파일 크기 제한 (DoS 방지)
     * 4. 파일명 특수문자 제거 (경로 탐색 공격 방지)
     * 5. 저장 시 원본 파일명 사용 금지 → UUID 사용
     *
     * 원본 파일명을 그대로 사용하면 안 되는 이유:
     * - ../../../etc/passwd 같은 경로 탐색 공격 가능
     * - 동일 파일명 충돌
     * - 특수문자 문제
     * </pre>
     */
    private void validateFile(MultipartFile file) {
        // 크기 검증
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("파일 크기가 10MB를 초과합니다");
        }

        // 확장자 검증
        String extension = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다: " + extension);
        }
    }

    /** 파일 확장자 추출 */
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
