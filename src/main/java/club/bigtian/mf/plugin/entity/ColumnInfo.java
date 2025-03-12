package club.bigtian.mf.plugin.entity;

import lombok.Data;

/**
 * 列信息
 *
 * @author daijunxiong
 * @date 2023/06/22
 */
@Data
public class ColumnInfo {
    /**
     * 列名
     */
    private String name;

    /**
     * 字段名
     */
    private String fieldName;
    private String fieldType;
    /**
     * 数据类型
     */
    private String type;
    /**
     * 列注释
     */
    private String comment;
    /**
     * 是主键
     */
    private boolean primaryKey;

    /**
     * 是否自动增长
     */
    private boolean isAutoIncrement;

    /**
     * 方法名称
     */
    private String methodName;

    /**
     * 是否必填
     */
    private boolean notNull;
    /**
     * 是否逻辑删除
     */
    private boolean logicDelete;

    private boolean tenant;

    private boolean version;

    private String insertValue;

    private String updateValue;

    private int size;

}
