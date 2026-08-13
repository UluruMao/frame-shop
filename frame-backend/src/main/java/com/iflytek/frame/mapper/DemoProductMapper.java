package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.entity.DemoProduct;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus {@link BaseMapper} gives full CRUD for {@link DemoProduct} for free.
 * Add custom queries in {@code resources/mapper/DemoProductMapper.xml} when needed.
 */
@Mapper
public interface DemoProductMapper extends BaseMapper<DemoProduct> {
}
