package dev.shortlink.web;

import dev.shortlink.link.LinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/links")
public class LinkApiController {

    private final LinkService service;

    public LinkApiController(LinkService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreateResponse> create(@RequestBody CreateRequest request) {
        LinkService.CreatedLink created = service.create(request.url(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateResponse(created.code(), created.shortUrl(), created.longUrl(), created.expireAt()));
    }

    public record CreateRequest(String url, Instant expiresAt) {
    }

    public record CreateResponse(String code, String shortUrl, String url, Instant expiresAt) {
    }
}
