package com.github.intellectualsites.kvantum.api.orm.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention( RetentionPolicy.RUNTIME )
@Target( ElementType.PARAMETER )
public @interface KvantumInsert
{

   String value();

}