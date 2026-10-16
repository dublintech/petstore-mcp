package com.dublintech.mcpproto1.mcp;

/**
 * An error whose message was written by the gateway and is safe to show the model. It deliberately has no cause:
 * Spring AI adds the root cause's message to the tool result, so a cause chain would leak whatever it contains.
 */
public class GatewayErrorException extends RuntimeException {

    public GatewayErrorException(String message) {
        super(message, null, false, false);
    }
}
