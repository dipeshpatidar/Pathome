package com.indore.pathome.spaces.dto;

public class AuthResponse {
    private String token;
    private Long userId;
    private String email;
    private String fullName;
    private String role;
    private Integer freeVisitsRemaining;
    private boolean hasLessorProfile;
    private String employeeRoleType;

    public AuthResponse() {}

    public AuthResponse(String token, Long userId, String email, String fullName, String role, Integer freeVisitsRemaining) {
        this(token, userId, email, fullName, role, freeVisitsRemaining, false);
    }

    public AuthResponse(String token, Long userId, String email, String fullName, String role, Integer freeVisitsRemaining, boolean hasLessorProfile) {
        this.token = token;
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.freeVisitsRemaining = freeVisitsRemaining;
        this.hasLessorProfile = hasLessorProfile;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Integer getFreeVisitsRemaining() { return freeVisitsRemaining; }
    public void setFreeVisitsRemaining(Integer freeVisitsRemaining) { this.freeVisitsRemaining = freeVisitsRemaining; }

    public boolean isHasLessorProfile() { return hasLessorProfile; }
    public void setHasLessorProfile(boolean hasLessorProfile) { this.hasLessorProfile = hasLessorProfile; }

    public String getEmployeeRoleType() { return employeeRoleType; }
    public void setEmployeeRoleType(String employeeRoleType) { this.employeeRoleType = employeeRoleType; }
}
