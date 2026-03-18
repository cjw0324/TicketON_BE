package org.codeNbug.mainserver.global.Redis.entry;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EntryTokenValidator {

	private final StringRedisTemplate redisTemplate;
	public static final String ENTRY_TOKEN_STORAGE_KEY_NAME = "ENTRY_TOKEN";

	public void validate(Long userId) {
		String storedValue = (String)redisTemplate.opsForHash().get(ENTRY_TOKEN_STORAGE_KEY_NAME, userId.toString());

		if (storedValue == null) {
			throw new AccessDeniedException("대기열을 통과하지 않은 사용자입니다.");
		}
	}
}