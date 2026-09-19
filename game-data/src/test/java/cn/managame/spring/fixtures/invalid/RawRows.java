package cn.managame.spring.fixtures.invalid;

import cn.managame.core.repository.SingleRepository;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings("rawtypes")
public interface RawRows extends SingleRepository { }
