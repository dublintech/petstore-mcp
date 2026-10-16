package com.dublintech.mcpproto1.store;

import com.dublintech.mcpproto1.common.ApiException;
import com.dublintech.mcpproto1.common.ErrorCode;
import com.dublintech.mcpproto1.common.FieldError;
import com.dublintech.mcpproto1.common.FieldError.Rule;
import com.dublintech.mcpproto1.pet.PetService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class StoreService {

    private final OrderRepository orderRepository;
    private final PetService petService;

    public StoreService(OrderRepository orderRepository, PetService petService) {
        this.orderRepository = orderRepository;
        this.petService = petService;
    }

    public Map<String, Integer> getInventory() {
        return petService.inventory();
    }

    public Order placeOrder(Order order) {
        if (order == null || order.petId() == null || !petService.exists(order.petId())) {
            throw ApiException.badRequest(ErrorCode.INVALID_ORDER, "Invalid Order: unknown petId", FieldError.of("petId", Rule.MUST_EXIST));
        }
        if (order.quantity() == null || order.quantity() < 1) {
            throw ApiException.badRequest(ErrorCode.INVALID_ORDER, "Invalid Order: quantity must be at least 1", FieldError.min("quantity", 1));
        }
        return orderRepository.save(order.withDefaults());
    }

    public Order getOrderById(long orderId) {
        requireValidId(orderId);
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
    }

    public void deleteOrder(long orderId) {
        requireValidId(orderId);
        if (!orderRepository.deleteById(orderId)) {
            throw ApiException.notFound(ErrorCode.ORDER_NOT_FOUND, "Order not found");
        }
    }

    private static void requireValidId(long orderId) {
        if (orderId < 1) {
            throw ApiException.badRequest(ErrorCode.INVALID_ID, "Invalid ID supplied", FieldError.min("orderId", 1));
        }
    }
}