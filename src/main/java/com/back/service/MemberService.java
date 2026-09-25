package com.back.service;

import com.back.entity.Member;
import com.back.exception.DomainException;
import com.back.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;

    public long count() {
        return memberRepository.count();
    }

    public Member join(String username, String password, String nickname) {
        findByUsername(username).ifPresent(
                member -> {
                    throw new DomainException("409-1", "이미 존재하는 username 입니다.");
                }
        );
        return memberRepository.save(new Member(username, password, nickname));
    }

    public Optional<Member> findByUsername(String username) {
        return memberRepository.findByUsername(username);
    }
}
