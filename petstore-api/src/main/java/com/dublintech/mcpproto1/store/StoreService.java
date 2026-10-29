package com.dublintech.mcpproto1.store;

import com.dublintech.mcpproto1.common.ApiException;
import com.dublintech.mcpproto1.common.ErrorCode;
import com.dublintech.mcpproto1.common.FieldError;
import com.dublintech.mcpproto1.common.FieldError.Rule;
import com.dublintech.mcpproto1.pet.PetService;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /** Places an order that belongs to {@code owner}, the user it's placed for. */
    public Order placeOrder(Order order, String owner) {
        if (order == null || order.petId() == null || !petService.exists(order.petId())) {
            throw ApiException.badRequest(ErrorCode.INVALID_ORDER, "Invalid Order: unknown petId", FieldError.of("petId", Rule.MUST_EXIST));
        }
        if (order.quantity() == null || order.quantity() < 1) {
            throw ApiException.badRequest(ErrorCode.INVALID_ORDER, "Invalid Order: quantity must be at least 1", FieldError.min("quantity", 1));
        }
        return orderRepository.save(order.withDefaults(), owner);
    }

    public List<Order> getOrders(String owner) {
        return orderRepository.findByOwner(owner);
    }

    public Order getOrderById(long orderId, String owner) {
        requireValidId(orderId);
        return ownedBy(orderId, owner);
    }

    public void deleteOrder(long orderId, String owner) {
        requireValidId(orderId);
        ownedBy(orderId, owner);
        if (!orderRepository.deleteById(orderId)) {
            throw orderNotFound();
        }
    }

    /**
     * The order, if it belongs to {@code owner}. Someone else's order is reported exactly like one that doesn't
     * exist (404, same code and message), so order IDs reveal nothing about other users' orders, not even that
     * they exist. A 403 would.
     */
    private Order ownedBy(long orderId, String owner) {
        return orderRepository.findById(orderId)
                .filter(owned -> owned.owner().equals(owner))
                .map(OrderRepository.OwnedOrder::order)
                .orElseThrow(StoreService::orderNotFound);
    }

    private static ApiException orderNotFound() {
        return ApiException.notFound(ErrorCode.ORDER_NOT_FOUND, "Order not found");
    }

    private static void requireValidId(long orderId) {
        if (orderId < 1) {
            throw ApiException.badRequest(ErrorCode.INVALID_ID, "Invalid ID supplied", FieldError.min("orderId", 1));
        }
    }
}