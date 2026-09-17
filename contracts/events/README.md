# Event contracts

Each JSON file is a canonical v1 example, not a message that the current application already publishes. The envelope fields are `eventId`, `eventType`, `eventVersion`, `occurredAt`, `aggregateId`, and `payload`. Times are UTC instants. Consumers select a payload type from `eventType` and `eventVersion`.

| Event | Topic | Kafka key |
| --- | --- | --- |
| `OrderPlaced`, `OrderConfirmed`, `OrderRejected`, `OrderCancelled` | `orders.order-events.v1` | `orderId` |
| `StockReserved`, `StockReservationFailed`, `StockReleased` | `inventory.stock-events.v1` | `orderId` |
| `LowStock` | `inventory.stock-events.v1` | `sku` |

For order-related events, `aggregateId` is the order UUID written as a string. For `LowStock`, it is the SKU. The Kafka headers carry `eventType` and, when available, the trace context in `traceparent`. The headers are transport metadata and are not fields of the JSON envelope.

An optional payload field may be added within version 1. Consumers ignore fields they do not know. Renaming or removing a field requires a new `eventVersion` and a period in which both versions are published. Services keep their own payload types; these JSON examples are the shared contract.

`StockReservationFailed.reason` is `Insufficient stock` or `Invalid order`. For an invalid order, `unavailableSkus` lists the offending SKUs and may be empty when no SKU can be named.
