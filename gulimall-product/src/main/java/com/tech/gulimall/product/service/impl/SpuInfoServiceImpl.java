package com.tech.gulimall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tech.gulimall.common.to.SkuEsModel;
import com.tech.gulimall.common.to.SkuReductionTo;
import com.tech.gulimall.common.to.SpuBoundTo;
import com.tech.gulimall.common.utils.BeanUtils;
import com.tech.gulimall.common.utils.PageUtils;
import com.tech.gulimall.common.utils.Query;
import com.tech.gulimall.common.utils.R;
import com.tech.gulimall.product.constant.enums.ProductStatusEnum;
import com.tech.gulimall.product.dao.SpuInfoDao;
import com.tech.gulimall.product.entity.po.*;
import com.tech.gulimall.product.entity.vo.BaseAttrs;
import com.tech.gulimall.product.entity.vo.Images;
import com.tech.gulimall.product.entity.vo.Skus;
import com.tech.gulimall.product.entity.vo.SpuSaveVo;
import com.tech.gulimall.product.feign.CouponFeignService;
import com.tech.gulimall.product.feign.SearchFeignService;
import com.tech.gulimall.product.service.*;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {

    @Autowired
    private SpuInfoDescService spuInfoDescService;

    @Autowired
    private SpuImagesService spuImagesService;

    @Autowired
    private ProductAttrValueService attrValueService;

    @Autowired
    private SkuInfoService skuInfoService;

    @Autowired
    private SkuImagesService skuImagesService;

    @Autowired
    private SkuSaleAttrValueService skuSaleAttrValueService;

    @Autowired
    private CouponFeignService couponFeignService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private SearchFeignService searchFeignService;

    @Autowired
    private AttrService attrService;


    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSpuInfo(SpuSaveVo spuSaveVo) {
        // 1.保存spu基本信息 pms_spu_info
        SpuInfoEntity spuInfoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(spuSaveVo, spuInfoEntity);
        BeanUtils.updateAuditFields(spuInfoEntity,true);
        this.baseMapper.insert(spuInfoEntity);
        // 2.保存spu的描述图片 pms_spu_info_desc
        SpuInfoDescEntity descEntity = new SpuInfoDescEntity();
        descEntity.setSpuId(spuInfoEntity.getId());
        List<String> decript = spuSaveVo.getDecript();
        descEntity.setDecript(String.join(",", decript));
        BeanUtils.updateAuditFields(descEntity, true);
        spuInfoDescService.saveSpuInfoDesc(descEntity);
        // 3.保存spu的图片集 pms_spu_images
        List<String> images = spuSaveVo.getImages();
        spuImagesService.saveImages(spuInfoEntity.getId(), images);
        // 4.保存spu的规格参数 pms_product_attr_value
        List<BaseAttrs> baseAttrs = spuSaveVo.getBaseAttrs();
        attrValueService.saveSpuBaseAttrs(spuInfoEntity.getId(), baseAttrs);
        // 5.保存spu的积分信息 gulimall_sms -> sms_spu_bounds
        SpuBoundTo spuBoundTo = new SpuBoundTo();
        BeanUtils.copyProperties(spuSaveVo.getBounds(), spuBoundTo);
        spuBoundTo.setSpuId(spuInfoEntity.getId());
        R saveR = couponFeignService.save(spuBoundTo);
        if (saveR.getCode() != 0) {
            log.error("远程保存spu积分信息失败！" + saveR.getMsg());
        }

        // 6.保存当前spu对应的所有sku信息
        List<Skus> skus = spuSaveVo.getSkus();
        if (null != skus && skus.size() > 0) {
            for (Skus sku : skus) {
                // 6.1 sku的基本信息 pms_sku_info
                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(sku,skuInfoEntity);
                skuInfoEntity.setSpuId(spuInfoEntity.getId());
                skuInfoEntity.setCatalogId(spuInfoEntity.getCatalogId());
                skuInfoEntity.setBrandId(spuInfoEntity.getBrandId());
                skuInfoEntity.setSaleCount(0L);
                for (Images image : sku.getImages()) {
                    if (image.getDefaultImg() == 1) {
                        skuInfoEntity.setSkuDefaultImg(image.getImgUrl());
                    }
                }
                BeanUtils.updateAuditFields(skuInfoEntity,true);
                skuInfoService.save(skuInfoEntity);
                // 6.2 sku的图片信息 pms_sku_images
                // filter 过滤的是 没有图片路径的，无须保存
                List<SkuImagesEntity> skuImagesEntities = sku.getImages().stream().map(skuImage -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    BeanUtils.copyProperties(skuImage, skuImagesEntity);
                    skuImagesEntity.setSkuId(skuInfoEntity.getSkuId());
                    return skuImagesEntity;
                }).filter(skuImage ->
                    StringUtils.isNotEmpty(skuImage.getImgUrl())
                ).collect(Collectors.toList());
                skuImagesService.saveBatch(skuImagesEntities);
                // 6.3 sku的销售属性信息 pms_sku_sale_attr_value
                skuSaleAttrValueService.saveSkuSaleAttrs(skuInfoEntity.getSkuId(), sku.getAttr());
                // 6.4 sku的优惠满减信息 gulimall_sms -> sms_sku_ladder(打折)    sms_sku_full_reduction(满减) sms_member_price(会员价格)
                SkuReductionTo skuReductionTo = new SkuReductionTo();
                BeanUtils.copyProperties(sku,skuReductionTo);
                skuReductionTo.setSkuId(skuInfoEntity.getSkuId());
                // 满减数量应该大于0，或 满多少大于0
                // BigDecimal的比较，返回1,则为大于0 skuReductionTo.getFullPrice().compareTo(new BigDecimal("0"))
                if (skuReductionTo.getFullCount() > 0 || skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) == 1) {
                    R r = couponFeignService.saveSkuReductionTo(skuReductionTo);
                    if (r.getCode() != 0) {
                        log.error("远程保存sku满减信息失败！" + r.getMsg());
                    }
                }
            }
        }
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        /**
         * status: 2
         * key:
         * brandId: 9
         * catelogId: 225
         */
        LambdaQueryWrapper<SpuInfoEntity> queryWrapper = new LambdaQueryWrapper<>();
        String key = (String) params.get("key");
        if (StringUtils.isNotEmpty(key)) {
            queryWrapper.and(w ->
                w.eq(SpuInfoEntity::getId, key).or().like(SpuInfoEntity::getSpuName, key)
            );
        }
        String status = (String) params.get("status");
        if (StringUtils.isNotEmpty(status)) {
            queryWrapper.eq(SpuInfoEntity::getPublishStatus, status);
        }
        String brandId = (String) params.get("brandId");
        if (StringUtils.isNotEmpty(brandId)) {
            queryWrapper.eq(SpuInfoEntity::getBrandId, brandId);
        }

        String catelogId = (String) params.get("catelogId");
        if (StringUtils.isNotEmpty(catelogId)) {
            queryWrapper.eq(SpuInfoEntity::getCatalogId, catelogId);
        }

        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                queryWrapper
        );

        return new PageUtils(page);
    }

    @Override
    public void upSpuForSearch(Long spuId) {
        // 1. 查出当前spuId对应的所有sku信息，品牌的名字
        List<SkuInfoEntity> skuInfoEntities = skuInfoService.getSkuBySpuId(spuId);

        // 4. 查出当前sku的所有可以被用来检索的规格属性
        List<ProductAttrValueEntity> productAttrValueEntities = attrValueService.list(new LambdaQueryWrapper<ProductAttrValueEntity>()
                .eq(ProductAttrValueEntity::getSpuId, spuId));
        List<Long> attrIds = productAttrValueEntities.stream().map(attr -> attr.getAttrId()).collect(Collectors.toList());
        List<Long> searchIds = attrService.selectSearchAttrIds(attrIds);
        HashSet<Long> searchIdsSet = new HashSet<>(searchIds);
        List<SkuEsModel.Attr> searchAttrs = productAttrValueEntities.stream().filter(
                entity -> searchIdsSet.contains(entity.getAttrId())
        ).map(entity -> {
            SkuEsModel.Attr attr = new SkuEsModel.Attr();
            BeanUtils.copyWithoutNull(entity, attr);
            return attr;
        }).collect(Collectors.toList());

        // 发送远程调用，库存系统查询是否有库存
        Map<Long, Boolean> stockMap = null;
        try {
            List<Long> skuIds = skuInfoEntities.stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());

        } catch (Exception e) {
            log.error("远程调用库存服务失败，原因是{}", e);
        }

        // 2. 封装每个sku的信息
        List<SkuEsModel> skuEsModelList = skuInfoEntities.stream().map(sku -> {
            SkuEsModel skuEsModel = new SkuEsModel();
            BeanUtils.copyProperties(sku, skuEsModel);
            skuEsModel.setSkuPrice(sku.getPrice());
            skuEsModel.setSkuImg(sku.getSkuDefaultImg());
            // 2.1 热度评分默认为0
            skuEsModel.setHotScore(0L);
            // 3. 查询品牌和分类的名字信息
            if (null != sku.getBrandId()) {
                BrandEntity brandEntity = brandService.getById(sku.getBrandId());
                if (null != brandEntity) {
                    skuEsModel.setBrandName(brandEntity.getName());
                    skuEsModel.setBrandImg(brandEntity.getLogo());
                }
            }
            if (null != sku.getCatalogId()) {
                CategoryEntity categoryEntity = categoryService.getById(sku.getCatalogId());
                if (null != categoryEntity) {
                    skuEsModel.setCatelogName(categoryEntity.getName());
                }
            }

            return skuEsModel;
        }).collect(Collectors.toList());
        // 5. 将数据发给es进行保存
        R r = searchFeignService.saveProductAsIndices(skuEsModelList);
        if (r.getCode() == 0) {
            this.baseMapper.upSpuStatus(spuId, ProductStatusEnum.SPU_UP.getCode());
        } else {
            log.error("商品远程es保存失败！");
        }

    }

}