# Petstore guide

## Pet statuses
- **available**: in the store and can be ordered.
- **pending**: an order is in progress; not yet handed over.
- **sold**: gone to a new home.

## Ordering rules
- An order must reference a pet that exists in the store.
- Quantity must be at least 1.
- New orders start as **placed** and incomplete; they move to **approved**, then **delivered**.

## Tags
Tags are free-form labels on pets. The sample data uses `tag1`, `tag2` and `tag3`.

## Sample users
`user1`, `user2` and `user3` exist for testing.
