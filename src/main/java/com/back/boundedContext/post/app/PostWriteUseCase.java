package com.back.boundedContext.post.app;

import com.back.boundedContext.member.domain.Member;
import com.back.boundedContext.post.domain.Post;
import com.back.boundedContext.post.out.PostRepository;
import com.back.global.eventPublisher.EventPublisher;
import com.back.shared.post.dto.PostDto;
import com.back.shared.post.event.PostCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostWriteUseCase {
    private final PostRepository postRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public Post write(Member author, String title, String content) {
        Post post = new Post(author, title, content);

        eventPublisher.publish(
                new PostCreatedEvent(
                        new PostDto(post)
                )
        );

        return postRepository.save(post);
    }
}
