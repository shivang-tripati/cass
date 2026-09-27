# Frontend Development Skill

## Role: Frontend Developer (Next.js, React, TypeScript)

## Core Responsibilities
1. Build responsive UI components with Tailwind CSS
2. Implement forms with validation (react-hook-form + zod)
3. Manage server-state with TanStack Query
4. Implement real-time features with WebSocket
5. Handle white-label branding via Next.js middleware
6. Create reusable component library
7. Ensure accessibility (a11y) and performance

## Key Files to Reference
- `/frontend/app/` - Next.js App Router
- `/frontend/components/` - React components
- `/frontend/lib/` - Utilities, API clients
- `/frontend/types/` - TypeScript interfaces
- `/frontend/middleware.ts` - White-label routing

### 1. API Client Setup
```java
// lib/api/client.ts
import axios from 'axios';

const api = axios.create({
    baseURL: process.env.NEXT_PUBLIC_API_URL,
    headers: {
        'Content-Type': 'application/json',
    },
    withCredentials: true, // For JWT cookies
});

// Add auth interceptor
api.interceptors.request.use((config) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

// Add response interceptor for token refresh
api.interceptors.response.use(
    (response) => response,
    async (error) => {
        if (error.response?.status === 401) {
            // Refresh token logic
            const newToken = await refreshToken();
            if (newToken) {
                error.config.headers.Authorization = `Bearer ${newToken}`;
                return api.request(error.config);
            }
        }
        return Promise.reject(error);
    }
);
export default api;
```
### 2. Data Fetching with TanStack Query
```java
// lib/hooks/useCampaigns.ts
import { useQuery } from '@tanstack/react-query';
import api from '@/lib/api/client';
import { Campaign } from '@/types/campaign';

export function useCampaigns() {
    return useQuery({
        queryKey: ['campaigns'],
        queryFn: async () => {
            const response = await api.get<Campaign[]>('/campaigns');
            return response.data;
        },
        staleTime: 60 * 1000, // 1 minute
    });
}

// Usage in component
const CampaignsPage = () => {
    const { data: campaigns, isLoading, error } = useCampaigns();
    
    if (isLoading) return <LoadingSpinner />;
    if (error) return <ErrorMessage error={error} />;
    
    return <CampaignList campaigns={campaigns} />;
};
```

### 3. Form Handling with React Hook Form
### 4. WebSocket Integration

## Component Patterns
1. Reusable Components
2. HOC for Authentication
```typescript
// lib/hoc/withAuth.tsx
import { useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { useAuth } from '@/lib/hooks/useAuth';

export function withAuth<P extends object>(Component: React.ComponentType<P>) {
    return function AuthenticatedComponent(props: P) {
        const { user, isLoading } = useAuth();
        const router = useRouter();
        
        useEffect(() => {
            if (!isLoading && !user) {
                router.push('/login');
            }
        }, [isLoading, user, router]);
        
        if (isLoading || !user) {
            return <LoadingSpinner />;
        }
        
        return <Component {...props} />;
    };
}
```

Performance Optimization
Use Next.js built-in optimizations:
next/image for images
next/dynamic for lazy loading
next/link for pre-fetching

Server Components for SEO
React optimizations:
React.memo for expensive components
useCallback for event handlers
useMemo for expensive calculations
useTransition for non-blocking updates

Data fetching:
Use TanStack Query for caching
Implement optimistic updates
Use getStaticProps / getServerSideProps appropriately

Bundle optimization:
Code splitting with dynamic imports
Tree shaking with ESM
Analyze bundle with @next/bundle-analyzer

## Styling with Tailwind CSS

Accessibility (a11y) Checklist
Semantic HTML elements (header, main, nav, article)
ARIA labels for interactive element
Keyboard navigation support
Focus management
Color contrast ratio ≥ 4.5:1
Screen reader-friendly content
Form labels and error messages
Alt text for images

## Error Handling
```typescript
// lib/hooks/useErrorHandler.ts
export function useErrorHandler() {
    const toast = useToast();
    
    return (error: unknown) => {
        if (axios.isAxiosError(error)) {
            const message = error.response?.data?.error?.message || 'An error occurred';
            toast({
                title: 'Error',
                description: message,
                variant: 'destructive',
            });
        } else {
            console.error('Unexpected error:', error);
            toast({
                title: 'Error',
                description: 'Something went wrong',
                variant: 'destructive',
            });
        }
    };
}
```
Common Debugging
React DevTools: Inspect component tree and props
Network Tab: Check API requests and responses
Console: Log state and props for debugging
Next.js Debug: NODE_OPTIONS='--inspect' next dev
Performance Profiling: Chrome DevTools Performance tab