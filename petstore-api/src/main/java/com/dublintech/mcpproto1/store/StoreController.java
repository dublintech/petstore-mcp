package com.dublintech.mcpproto1.store;

import com.dublintech.mcpproto1.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(path = "/v2/store", produces = MediaType.APPLICATION_JSON_VALUE)
public class StoreController {

    private static final Logger log = LoggerFactory.getLogger(StoreController.class);

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping("/inventory")
    public Map<String, Integer> getInventory() {
        return storeService.getInventory();
    }

    /**
     * Also records who bought: the user ({@code sub}) and the program acting for them ({@code act}), both from the
     * exchanged token. The log shows what authority was used, not whether the user meant it; that needs step-up
     * consent (roadmap step 8).
     */
    @PostMapping(path = "/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Order placeOrder(@RequestBody Order order, @AuthenticationPrincipal Jwt token) {
        Order placed = storeService.placeOrder(order);
        Object actor = token.getClaimAsMap("act") == null ? null : token.getClaimAsMap("act").get("sub");
        log.info("Order {} for pet {} placed by user={} via actor={}", placed.id(), placed.petId(),
                forLog(token.getSubject()), forLog(actor));
        return placed;
    }

    /** Claims come from a signed token, but keep a log line to one line anyway. */
    private static String forLog(Object value) {
        return value == null ? null : value.toString().replaceAll("[\\r\\n\\t]", " ");
    }

    @GetMapping("/order/{orderId}")
    public Order getOrderById(@PathVariable long orderId) {
        return storeService.getOrderById(orderId);
    }

    @DeleteMapping("/order/{orderId}")
    public ApiResponse deleteOrder(@PathVariable long orderId) {
        storeService.deleteOrder(orderId);
        return ApiResponse.ok(String.valueOf(orderId));
    }
}