package kr.co.example.swagger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Swagger / OpenAPI 3.0 설정 — API 문서 자동화.
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  springdoc-openapi — Spring Boot 3.x 호환                           │
 * │  의존성: org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0    │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  문서 URL:                                                          │
 * │  - Swagger UI:  http://localhost:8080/swagger-ui/index.html         │
 * │  - JSON 스펙:   http://localhost:8080/v3/api-docs                   │
 * │  - YAML 스펙:   http://localhost:8080/v3/api-docs.yaml              │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 주의: Spring Boot 2.x는 springfox (Swagger 2.x) 사용
 *       Spring Boot 3.x는 springdoc-openapi (Swagger 3.x / OpenAPI 3.0) 사용
 *       springfox는 Boot 3.x와 호환되지 않음
 *
 * application.yml:
 *   springdoc:
 *     swagger-ui:
 *       path: /swagger-ui.html    # Swagger UI 경로 (기본: /swagger-ui/index.html)
 *       tags-sorter: alpha        # 태그 정렬
 *       operations-sorter: method # 메서드별 정렬
 *     api-docs:
 *       path: /v3/api-docs        # OpenAPI 스펙 경로
 * </pre>
 */
@Configuration
public class SwaggerConfig {

    /**
     * OpenAPI 전역 설정 — API 제목, 설명, 버전, 인증 스키마.
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring Tech Examples API")
                        .description("주니어 백엔드 개발자를 위한 기술 예제 API")
                        .version("1.0.0"))
                // JWT Bearer 인증 스키마 등록
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT 토큰을 입력하세요 (Bearer 접두사 불필요)")));
    }

    // ====================================================================
    // 컨트롤러 어노테이션 사용 예시
    // ====================================================================

    /**
     * @Tag — 컨트롤러 그룹 (Swagger UI에서 그룹으로 표시).
     * @Operation — 개별 API 엔드포인트 설명.
     * @Parameter — 파라미터 설명.
     * @ApiResponse — 응답 코드별 설명.
     */
    @Tag(name = "사용자 관리", description = "사용자 CRUD API")
    @RestController
    @RequestMapping("/api/swagger-example")
    public static class SwaggerExampleController {

        @Operation(
                summary = "사용자 단건 조회",          // API 한 줄 설명
                description = "사용자 ID로 상세 정보를 조회합니다" // 상세 설명
        )
        @ApiResponses({
                @ApiResponse(responseCode = "200", description = "조회 성공",
                        content = @Content(schema = @Schema(implementation = UserDto.class))),
                @ApiResponse(responseCode = "404", description = "사용자 없음"),
                @ApiResponse(responseCode = "401", description = "인증 필요")
        })
        @GetMapping("/{id}")
        public ResponseEntity<UserDto> getUser(
                @Parameter(description = "사용자 ID", required = true, example = "1")
                @PathVariable Long id) {
            return ResponseEntity.ok(new UserDto(id, "홍길동", "hong@example.com"));
        }

        @Operation(summary = "사용자 목록 조회")
        @GetMapping
        public ResponseEntity<String> getUsers(
                @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
                @RequestParam(defaultValue = "0") int page,
                @Parameter(description = "페이지 크기", example = "10")
                @RequestParam(defaultValue = "10") int size) {
            return ResponseEntity.ok("OK");
        }

        /** Swagger에 표시될 DTO 스키마 */
        @Schema(description = "사용자 응답 DTO")
        record UserDto(
                @Schema(description = "사용자 ID", example = "1") Long id,
                @Schema(description = "이름", example = "홍길동") String name,
                @Schema(description = "이메일", example = "hong@example.com") String email
        ) {
        }
    }
}
