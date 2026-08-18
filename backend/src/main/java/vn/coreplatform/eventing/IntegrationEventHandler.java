package vn.coreplatform.eventing;

/**
 * SPI cho consumer của integration event (E6-S04). Implementation tự dùng
 * {@link OutboxService#consumeOnce(String, java.util.UUID, Runnable)} để bảo đảm
 * side effect chỉ chạy một lần cho mỗi (consumer, event).
 */
public interface IntegrationEventHandler {
  String eventType();
  void handle(IntegrationEvent event);
}
