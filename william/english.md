Here is the complete, structured, and English-commented code solution tailored for **PostgreSQL**.

### 1\. Database Schema (PostgreSQL DDL)

PostgreSQL handles UUIDs natively, which is excellent for performance.

```sql
-- Enable UUID extension if not already enabled (optional, but good practice)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Main Table: Feature Toggles
CREATE TABLE feature_toggles (
    id UUID PRIMARY KEY,                     -- Physical Primary Key
    feature_key VARCHAR(50) NOT NULL,        -- Business Unique Key
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(255),
    
    -- Audit Fields
    created_by VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(50),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_feature_key UNIQUE (feature_key)
);

-- 2. Relation Table: Allowed Groups (One-to-Many)
CREATE TABLE feature_allowed_groups (
    feature_toggle_id UUID NOT NULL,
    group_name VARCHAR(50) NOT NULL,

    -- Foreign Key linking to the UUID of the main table
    CONSTRAINT fk_feature_groups_toggle 
        FOREIGN KEY (feature_toggle_id) 
        REFERENCES feature_toggles (id) 
        ON DELETE CASCADE
);

-- Index for performance when joining
CREATE INDEX idx_feature_allowed_groups_id ON feature_allowed_groups(feature_toggle_id);

-- Initial Data Example
-- Note: In PG, you can use gen_random_uuid() if the extension is enabled, or a literal string.
INSERT INTO feature_toggles (id, feature_key, is_enabled, description, created_by, created_at) 
VALUES ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'GLOBAL_SVC', true, 'Global Service Switch', 'SYSTEM', NOW());

INSERT INTO feature_allowed_groups (feature_toggle_id, group_name) 
VALUES ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'INTERNAL');
```

-----

### 2\. Entity Layer

#### `BaseEntity.java`

Handles UUID generation and Audit fields.

```java
package com.company.project.entity;

import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class) // Enables JPA Auditing
public abstract class BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid-hibernate-generator")
    @GenericGenerator(name = "uuid-hibernate-generator", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

#### `FeatureToggle.java`

The core domain model.

```java
package com.company.project.entity;

import lombok.*;
import javax.persistence.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@EqualsAndHashCode(callSuper = true)
@Data
@Entity
@Table(name = "feature_toggles", 
       indexes = {@Index(name = "idx_feature_key", columnList = "feature_key", unique = true)})
@NoArgsConstructor
@AllArgsConstructor
public class FeatureToggle extends BaseEntity {

    /**
     * Business Unique Key (e.g., "GLOBAL_SVC", "NEW_PAYMENT")
     */
    @Column(name = "feature_key", length = 50, unique = true, nullable = false)
    private String featureKey;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled;

    @Column(name = "description")
    private String description;

    /**
     * Whitelist of allowed user groups.
     * Mapped to a separate table 'feature_allowed_groups' via UUID FK.
     * FetchType.EAGER is used because we always need groups when checking toggles.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "feature_allowed_groups",
            joinColumns = @JoinColumn(name = "feature_toggle_id", referencedColumnName = "id")
    )
    @Column(name = "group_name")
    private Set<String> allowedGroups = new HashSet<>();

    /**
     * Checks if a user's groups are permitted to access this feature.
     * Logic:
     * 1. If allowedGroups is empty -> Feature is open to everyone.
     * 2. If allowedGroups has values -> User must have at least one matching group.
     */
    public boolean isGroupAllowed(List<String> userGroups) {
        if (this.allowedGroups == null || this.allowedGroups.isEmpty()) {
            return true; // Open to all
        }
        if (userGroups == null || userGroups.isEmpty()) {
            return false; // Restricted, but user has no groups
        }
        // Check for intersection
        return userGroups.stream().anyMatch(this.allowedGroups::contains);
    }
}
```

-----

### 3\. Repository Layer

#### `FeatureToggleRepository.java`

```java
package com.company.project.repository;

import com.company.project.entity.FeatureToggle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FeatureToggleRepository extends JpaRepository<FeatureToggle, UUID> {
    
    /**
     * Finds a feature toggle by its business key.
     * @param featureKey The unique string key (e.g., "GLOBAL_SVC")
     * @return Optional containing the feature toggle
     */
    Optional<FeatureToggle> findByFeatureKey(String featureKey);
}
```

-----

### 4\. Service Layer

#### `FeatureService.java` (Interface)

```java
package com.company.project.service;

import com.company.project.util.UserContext;
import java.util.List;

public interface FeatureService {
    /**
     * Checks if a feature is enabled for the specific context.
     * Uses caching for performance.
     */
    boolean isAllowed(String featureKey, UserContext user);

    /**
     * Admin operation to create or update a feature toggle.
     * Automatically handles cache invalidation.
     */
    void updateFeature(String key, boolean enabled, List<String> groups, String operator);
}
```

#### `FeatureServiceImpl.java` (Implementation)

```java
package com.company.project.service.impl;

import com.company.project.entity.FeatureToggle;
import com.company.project.repository.FeatureToggleRepository;
import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FeatureServiceImpl implements FeatureService {

    private final FeatureToggleRepository featureRepo;

    // Local Cache: Expire 1 minute after write to ensure eventual consistency
    private final Cache<String, FeatureToggle> localCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Override
    public boolean isAllowed(String featureKey, UserContext user) {
        // 1. Admin Bypass (God Mode)
        if (user != null && user.isAdmin()) {
            return true;
        }

        // 2. Retrieve from Cache (Cache-Aside pattern)
        FeatureToggle toggle = localCache.get(featureKey, key -> 
            featureRepo.findByFeatureKey(key).orElse(null)
        );

        // 3. Validation: Check if exists and is globally enabled
        if (toggle == null || !toggle.isEnabled()) {
            return false;
        }

        // 4. Group Strategy Validation
        List<String> userGroups = (user != null) ? user.getGroups() : null;
        return toggle.isGroupAllowed(userGroups);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFeature(String key, boolean enabled, List<String> groups, String operator) {
        FeatureToggle toggle = featureRepo.findByFeatureKey(key)
                .orElse(new FeatureToggle());
        
        // Initialize key and creator if new
        if (toggle.getFeatureKey() == null) {
            toggle.setFeatureKey(key);
            toggle.setCreatedBy(operator);
        }

        // Update fields
        toggle.setEnabled(enabled);
        toggle.setUpdatedBy(operator);
        
        // Update Whitelist Groups
        if (groups != null) {
            toggle.setAllowedGroups(new HashSet<>(groups));
        } else {
            toggle.getAllowedGroups().clear();
        }

        featureRepo.save(toggle);

        // Invalidate cache to force database fetch on next request
        localCache.invalidate(key);
    }
}
```

-----

### 5\. Controller Layer

#### `UserApiController.java`

Handles the "Current User" request which serves as the frontend initialization point.

```java
package com.company.project.controller;

import com.company.project.dto.FeatureDTO; // Assume DTOs exist
import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserApiController {

    private final FeatureService featureService;

    @GetMapping("/current")
    public ResponseEntity<FeatureDTO.CurrentUserResponse> getCurrentUser(HttpServletRequest request) {
        // Extract context from token/session
        UserContext user = UserContext.fromRequest(request);

        FeatureDTO.CurrentUserResponse resp = new FeatureDTO.CurrentUserResponse();
        resp.setUserId(user.getUserId());
        resp.setAdmin(user.isAdmin());
        
        // Determine if the global service is available for THIS specific user
        // Logic includes: Admin bypass, DB toggle status, and Group Whitelist
        boolean isOpen = featureService.isAllowed("GLOBAL_SVC", user);
        resp.setGlobalServiceOpen(isOpen);

        return ResponseEntity.ok(resp);
    }
}
```

#### `AdminFeatureController.java`

```java
package com.company.project.controller;

import com.company.project.dto.FeatureDTO;
import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/admin/features")
@RequiredArgsConstructor
public class AdminFeatureController {

    private final FeatureService featureService;

    @PostMapping("/update")
    public ResponseEntity<String> updateFeature(@RequestBody FeatureDTO.UpdateRequest req, 
                                                HttpServletRequest request) {
        UserContext admin = UserContext.fromRequest(request);
        
        if (!admin.isAdmin()) {
            return ResponseEntity.status(403).body("Access Denied");
        }

        featureService.updateFeature(
                req.getFeatureKey(),
                req.isEnabled(),
                req.getAllowedGroups(),
                "ADMIN_" + admin.getUserId() // Operator ID for audit
        );

        return ResponseEntity.ok("Feature updated successfully");
    }
}
```

-----

### 6\. Configuration & Utilities

#### `UserContext.java`

```java
package com.company.project.util;

import lombok.Builder;
import lombok.Data;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Data
@Builder
public class UserContext {
    private Long userId;
    private boolean isAdmin;
    private List<String> groups;

    /**
     * Extract user context from the HTTP Request (e.g., JWT Token)
     */
    public static UserContext fromRequest(HttpServletRequest request) {
        // Implementation depends on security framework (e.g., Spring Security)
        return UserContext.builder()
                .userId(1001L)
                .isAdmin(false) 
                .groups(List.of("BETA_TESTER")) 
                .build();
    }
}
```

#### `GlobalAccessInterceptor.java`

The safety net to block access at the server level if the frontend check is bypassed.

```java
package com.company.project.interceptor;

import com.company.project.service.FeatureService;
import com.company.project.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
@RequiredArgsConstructor
public class GlobalAccessInterceptor implements HandlerInterceptor {

    private final FeatureService featureService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 1. Whitelist essential endpoints (User Init, Login, Static resources)
        if (uri.startsWith("/api/user/current") || uri.startsWith("/auth")) {
            return true;
        }

        // 2. Check Permissions
        UserContext user = UserContext.fromRequest(request);
        boolean allowed = featureService.isAllowed("GLOBAL_SVC", user);

        if (allowed) {
            return true;
        }

        // 3. Block Access
        response.setStatus(503);
        response.getWriter().write("{\"error\": \"Service Unavailable\"}");
        return false;
    }
}
```