package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
