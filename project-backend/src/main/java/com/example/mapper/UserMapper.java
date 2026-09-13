package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/** 用户数据访问接口。 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
