/**
 * ChunkScanner 公共 API。
 *
 * <p>本包是外部模组接入 ChunkScanner 的<b>唯一稳定契约</b>。
 * 入口见 {@link com.billy65536.chunkscanner.api.ChunkScannerApi}。</p>
 *
 * <h2>组成</h2>
 * <ul>
 *   <li>{@link com.billy65536.chunkscanner.api.DatabaseApi} —— 数据库查询/列表、加载、导出、GUI</li>
 *   <li>{@link com.billy65536.chunkscanner.api.NavigationApi} —— 导航入队、独立导航实例、到达条件注册</li>
 *   <li>{@link com.billy65536.chunkscanner.api.RegistryApi} —— 分析器、数据库视图、存储引擎注册</li>
 * </ul>
 *
 * <h2>稳定性</h2>
 * <p>本包内的公开签名在同一主版本内向后兼容。包外的类不属于公共契约，
 * 外部模组不应直接依赖，除非它是 API 方法的返回类型或参数类型。</p>
 */
package com.billy65536.chunkscanner.api;
