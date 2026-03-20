package org.codenbug.user.security.service;

import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * JWT 클레임만으로 구성된 경량 UserDetails.
 *
 * DB 조회 없이 JWT payload에서 직접 생성된다.
 * stateless 모드(jwt.stateless=true)인 서버에서 사용되며,
 * queue-server처럼 userId만 필요하고 User 엔티티 전체가 불필요한 경우에 적합하다.
 */
@Getter
@RequiredArgsConstructor
public class JwtUserDetails implements UserDetails {

    private final Long userId;
    private final String identifier;  // email 또는 "socialId:provider"
    private final String role;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return identifier;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
