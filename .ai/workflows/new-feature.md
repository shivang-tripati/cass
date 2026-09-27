# New Feature Workflow

## Phase 1: Planning
1. **Review PRD**: Understand requirements
2. **Create design doc**: Architecture decisions
3. **Identify dependencies**: What needs to change
4. **Create tasks**: Break down into subtasks
5. **Estimate effort**: Time required

## Phase 2: Development
1. **Create branch**: `feature/[feature-name]`
2. **Implement**:
   - Database migrations (if needed)
   - Backend implementation
   - Frontend implementation
   - API documentation
3. **Write tests**:
   - Unit tests
   - Integration tests
   - E2E tests
4. **Self-review**: Check code against standards

## Phase 3: Testing
1. **Local testing**: Run locally
2. **Integration testing**: Test with dependencies
3. **Performance testing**: Check performance impact
4. **Security testing**: Verify security

## Phase 4: Review
1. **Create PR**: Submit for review
2. **Address feedback**: Make requested changes
3. **Get approval**: At least 2 approvals
4. **Merge**: Merge to develop/main

## Phase 5: Deployment
1. **Staging**: Deploy to staging environment
2. **Staging testing**: Test in staging
3. **Production**: Deploy to production
4. **Monitor**: Check logs and metrics

## Phase 6: Post-Deployment
1. **Document**: Update documentation
2. **Monitor**: Watch for issues
3. **Feedback**: Gather user feedback
4. **Iterate**: Improve based on feedback

## Required Files
- [ ] Database migration
- [ ] Backend code
- [ ] Frontend code
- [ ] Tests
- [ ] Documentation
- [ ] README updates

## Time Estimates
- Planning: [hours]
- Development: [hours]
- Testing: [hours]
- Review: [hours]
- Deployment: [hours]

## Dependencies
- [Dependency 1]
- [Dependency 2]