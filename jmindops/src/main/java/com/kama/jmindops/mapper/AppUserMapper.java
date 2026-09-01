package com.kama.jmindops.mapper;

import com.kama.jmindops.model.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper {
    int insert(AppUser user);

    AppUser selectByUsername(String username);

    AppUser selectById(String id);
}
