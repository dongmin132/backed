package dev.backed.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhooks;
    private final WebhookEvents events;
    private final ObjectProvider<WebhookSignatureVerifier> verifier;

    public WebhookController(WebhookService webhooks, WebhookEvents events,
                             ObjectProvider<WebhookSignatureVerifier> verifier) {
        this.webhooks = webhooks;
        this.events = events;
        this.verifier = verifier;
    }

    /** PayPal's delivery endpoint. Public and without CSRF (see SecurityConfig). */
    @PostMapping("/webhooks/paypal")
    @ResponseBody
    public ResponseEntity<Map<String, String>> receive(@RequestBody(required = false) String body,
                                                       @RequestHeader HttpHeaders headers) {
        WebhookSignatureVerifier check = verifier.getIfAvailable();
        if (check != null) {
            try {
                if (!check.verify(headers, body == null ? "" : body)) {
                    return ResponseEntity.badRequest().body(Map.of("status", "rejected", "detail", "signature not verified"));
                }
            } catch (RuntimeException e) {
                log.warn("Webhook signature check could not run: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "retry", "detail", "signature check unavailable"));
            }
        }
        try {
            WebhookService.Receipt receipt = webhooks.receive(body);
            return ResponseEntity.ok(Map.of(
                    "status", receipt.outcome().name().toLowerCase(),
                    "event_id", receipt.eventId(),
                    "detail", receipt.detail()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "rejected", "detail", e.getMessage()));
        }
    }

    /** Staff view of received events. Lives under /close so it gets the close page's access rule. */
    @GetMapping("/close/webhooks")
    public String list(Model model) {
        model.addAttribute("events", events.recent(200));
        model.addAttribute("total", events.count());
        return "webhook/events";
    }
}
