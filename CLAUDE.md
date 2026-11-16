
# Baked & Browned Engineering Rules

## Project
Online bakery ordering platform for Dhaka, Bangladesh.

## Stack
- Java 25, Spring Boot 4.x
- PostgreSQL, Flyway, Spring Data JPA
- Angular 22, TypeScript, Tailwind CSS 4
- Docker, GitHub Actions

## Architecture
- Modular monolith
- Package-by-feature backend
- REST API under /api/v1
- Angular standalone components
- Strict TypeScript
- Constructor injection in Java
- DTOs at API boundaries

## Business Rules
- Currency: BDT
- Time zone: Asia/Dhaka
- Backend owns all prices
- Customizations must be validated server-side
- Orders require inventory and slot reservations
- Checkout must be idempotent
- Never trust browser payment redirects
- Preserve immutable order snapshots

## Security
- Spring Security with JWT
- Short-lived access tokens
- Rotating refresh tokens
- No secrets in source control
- Customer/admin authorization
- Never expose JPA entities directly

## Database
- Flyway owns schema migrations
- Use NUMERIC for money
- Use TIMESTAMPTZ for timestamps
- Never use ddl-auto=update in production

## Quality
- Write tests for business logic
- Integration-test transactional workflows
- Run builds before marking work complete
- Never suppress compiler or lint errors
- Do not invent dependencies or APIs
- Verify framework-specific syntax
- Prefer small, reviewable changes

## Workflow
1. Inspect existing code.
2. Propose implementation steps.
3. Implement one vertical slice.
4. Run tests and static checks.
5. Report changes and remaining risks.
