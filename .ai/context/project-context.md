# OBD Platform - Project Context

## Project Overview
**Name**: OBD (Outbound Dialer) Platform
**Type**: Enterprise-grade Bulk Voice Call System
**Version**: 1.0.0
**Repository**: https://github.com/your-org/obd-platform

## Core Business Value
- White-label multi-tenant voice campaign platform
- 3 campaign types: Playfile, DTMF, Connect-by-Agent
- Predictive dialer with <3% abandonment rate
- Real-time monitoring and reporting

## Key Stakeholders
- **Platform Admin**: Manages resellers, numbers, audio approvals
- **Reseller**: White-label branding, tenant management
- **Tenant Admin**: Campaign management, agent oversight
- **Agent**: Handles calls, logs dispositions
- **Super Admin**: Platform-wide controls

## Target Metrics
- **Concurrent Calls**: 10,000+
- **Call Setup Latency**: <500ms (P95)
- **API Response Time**: <300ms (P95)
- **Availability**: 99.9% uptime
- **Abandonment Rate**: <3%

## Development Philosophy
- **Modular Monolith**: Start as monolith, extract when needed
- **Event-Driven**: Kafka for async processing
- **Defense in Depth**: RLS + JPA @Filter for tenant isolation
- **AI-Ready**: Comprehensive context files for efficient AI coding

## Current Sprint Goals
- [ ] Core telephony foundation (ARI integration)
- [ ] Multi-tier tenant management
- [ ] Audio upload and approval workflow
- [ ] Basic campaign execution
- [ ] Predictive dialer engine prototype

## Technical Constraints
- Must support white-label domains (CNAME)
- Must enforce DND checks on every outbound call
- Must maintain call recordings for compliance (30 days to 7 years)
- Must support time-zone aware calling hours
- Must handle retry logic for failed calls