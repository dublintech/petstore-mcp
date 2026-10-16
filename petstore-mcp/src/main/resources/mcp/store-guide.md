# Petstore guide

## Pet statuses
- **available**: in the store and can be ordered.
- **pending**: an order is in progress; not yet handed over.
- **sold**: gone to a new home.

## Buying a pet
- Use **buyPet**; it is the only way to buy. It works only for **available** pets.
- Buying places an order and marks the pet **pending**, so nobody else can buy it.
- Quantity must be at least 1.
- New orders start as **placed** and incomplete; they move to **approved**, then **delivered**.
- See the categories resource for the valid **searchPets** category filters.

## Tags
Tags are free-form labels on pets. The sample data uses `tag1`, `tag2` and `tag3`.

## Sample users
`user1`, `user2` and `user3` exist for testing.
