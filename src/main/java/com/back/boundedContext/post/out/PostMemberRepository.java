package com.back.boundedContext.post.out;

import com.back.boundedContext.post.domain.PostMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostMemberRepository extends JpaRepository<PostMember, Integer> {
    Optional<PostMember> findByUsername(String username);
}
