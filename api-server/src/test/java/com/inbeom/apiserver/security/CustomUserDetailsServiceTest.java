package com.inbeom.apiserver.security;

import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link CustomUserDetailsService} 단위 테스트.
 *
 * <p>username 으로 사용자를 찾아 {@link CustomUserDetails} 로 감싸는 정상 경로와,
 * 사용자가 없을 때 {@link UsernameNotFoundException} 을 던지는 경로를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomUserDetailsService 단위 테스트")
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    private User sampleUser() {
        return User.builder()
                .id(1L)
                .username("admin")
                .email("admin@example.com")
                .password("$2a$10$encodedPassword")
                .name("관리자")
                .build();
    }

    @Test
    @DisplayName("사용자가 존재하면 CustomUserDetails 로 감싸 반환한다")
    void loadUserByUsernameReturnsCustomUserDetails() {
        // Given
        User user = sampleUser();
        given(userRepository.findByUsername("admin")).willReturn(Optional.of(user));

        // When
        UserDetails details = customUserDetailsService.loadUserByUsername("admin");

        // Then
        assertThat(details).isInstanceOf(CustomUserDetails.class);
        assertThat(details.getUsername()).isEqualTo("admin");
        assertThat(details.getPassword()).isEqualTo("$2a$10$encodedPassword");
        assertThat(((CustomUserDetails) details).getUser()).isSameAs(user);
        verify(userRepository).findByUsername("admin");
    }

    @Test
    @DisplayName("사용자가 존재하면 ROLE_USER 권한이 부여된다")
    void loadUserByUsernameGrantsRoleUser() {
        // Given
        given(userRepository.findByUsername("admin")).willReturn(Optional.of(sampleUser()));

        // When
        UserDetails details = customUserDetailsService.loadUserByUsername("admin");

        // Then
        assertThat(details.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("사용자가 없으면 UsernameNotFoundException 을 던진다")
    void loadUserByUsernameThrowsWhenUserNotFound() {
        // Given
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found: ghost");
    }

    @Test
    @DisplayName("username 이 null 이어도 조회 결과가 없으면 UsernameNotFoundException 을 던진다")
    void loadUserByUsernameThrowsForNullUsername() {
        // Given
        given(userRepository.findByUsername(null)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found: null");
    }
}
