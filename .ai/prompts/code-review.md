# Code Review Prompt

## Review Context
- **PR Title**: [Pull request title]
- **PR Link**: [Link]
- **Module**: [Module name]
- **Feature**: [Feature name]

## Files Changed
[list of changed files]


## Review Focus Areas
1. [Focus area 1]
2. [Focus area 2]
3. [Focus area 3]

## Checklist

### Code Quality
- [ ] Follows project conventions
- [ ] No code duplication
- [ ] Proper naming
- [ ] No magic numbers
- [ ] Single responsibility principle
- [ ] Proper error handling
- [ ] Proper logging

### Architecture
- [ ] Fits modular monolith pattern
- [ ] Uses proper design patterns
- [ ] No tight coupling
- [ ] Proper abstraction levels
- [ ] Event-driven where appropriate

### Database
- [ ] Proper indexes
- [ ] No N+1 queries
- [ ] Transaction boundaries correct
- [ ] Partitioning considered
- [ ] RLS enforced

### Security
- [ ] Tenant isolation maintained
- [ ] No SQL injection
- [ ] Proper authentication/authorization
- [ ] No sensitive data in logs
- [ ] Input validation

### Performance
- [ ] No O(n^2) operations
- [ ] Caching where appropriate
- [ ] Async where needed
- [ ] Batch operations used
- [ ] Connection pooling configured

### Testing
- [ ] Unit tests cover core logic
- [ ] Integration tests for DB operations
- [ ] Edge cases tested
- [ ] Negative scenarios tested
- [ ] Test coverage >80%

### Documentation
- [ ] API documented
- [ ] Complex logic explained
- [ ] README updated
- [ ] Swagger/OpenAPI updated

## Issues Found
1. [Issue 1]
2. [Issue 2]
3. [Issue 3]

## Suggestions
1. [Suggestion 1]
2. [Suggestion 2]
3. [Suggestion 3]

## Decision
[Approve/Request Changes/Reject]