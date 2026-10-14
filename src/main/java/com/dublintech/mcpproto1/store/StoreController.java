package com.dublintech.mcpproto1.store;

import com.dublintech.mcpproto1.common.ApiResponse;
import org.springframework.http.MediaType;
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

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping("/inventory")
    public Map<String, Integer> getInventory() {
        return storeService.getInventory();
    }

    @PostMapping(path = "/order", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Order placeOrder(@RequestBody Order order) {
        return storeService.placeOrder(order);
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