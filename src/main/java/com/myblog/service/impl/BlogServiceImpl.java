package com.myblog.service.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.myblog.dao.BlogMapper;
import com.myblog.dao.CategoryMapper;
import com.myblog.dao.TagMapper;
import com.myblog.lucene.BlogIndex;
import com.myblog.lucene.BlogIterator;
import com.myblog.model.Blog;
import com.myblog.model.Category;
import com.myblog.model.Tag;
import com.myblog.service.IAsyncService;
import com.myblog.service.IBlogService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.search.suggest.Lookup.LookupResult;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * Created by Zephery on 2017/1/18.
 */
@Service("blogService")
public class BlogServiceImpl implements IBlogService {
    private static final Logger logger = LoggerFactory.getLogger(BlogServiceImpl.class);
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private CategoryMapper categoryMapper;
    @Resource
    private TagMapper tagMapper;
    @Resource
    private IAsyncService asyncService;
    private BlogIndex blogIndex = new BlogIndex();

    /**
     * Set截取
     *
     * @param objSet
     * @param size
     */
    private static void ssubSet(Set<String> objSet, int size) {
        if (CollectionUtils.isEmpty(objSet)) {
            Collections.emptySet();
        }
        ImmutableSet.copyOf(Iterables.limit(objSet, size));
    }

    /**
     * lookup
     *
     * @param suggester
     * @param keyword
     * @throws IOException
     */
    private static List<String> lookup(AnalyzingInfixSuggester suggester, String keyword
    ) throws IOException {
        //先以contexts为过滤条件进行过滤，再以title为关键字进行筛选，根据weight值排序返回前2条
        //第3个布尔值即是否每个Term都要匹配，第4个参数表示是否需要关键字高亮
        List<LookupResult> results = suggester.lookup(keyword, 20, true, true);
        List<String> list = new ArrayList<>();
        for (LookupResult result : results) {
            list.add(result.key.toString());
            //从payload中反序列化出Blog对象
//            BytesRef bytesRef = result.payload;
//            InputStream is = Tools.bytes2InputStream(bytesRef.bytes);
//            Blog blog = (Blog) Tools.deSerialize(is);
//            System.out.println("blog-Name:" + blog.getTitle());
//            System.out.println("blog-Content:" + blog.getContent());
//            System.out.println("blog-image:" + blog.getImageurl());
//            System.out.println("blog-numberSold:" + blog.getHits());
        }
        return list;
    }

    @Override
    public List<Blog> getAllBlog() {
        List<Blog> blogs = blogMapper.getAllBlog();
        for (Blog blog : blogs) {
            blog.setCategory(categoryMapper.selectByPrimaryKey(blog.getCategoryid()));
        }
        return blogs;
    }

    @Override
    public List<Blog> getByCategoryId(int categoryid) {
        List<Blog> blogs = blogMapper.getByCategoryId(categoryid);
        return blogs;
    }

    @Override
    public Blog getBlogDetail(Integer blogid) {
        Blog blog = blogMapper.selectByPrimaryKey(blogid);
        if (blog == null) {
            return null;
        }
        Category category = categoryMapper.selectByPrimaryKey(blog.getCategoryid());
        blog.setCategory(category);
        List<Tag> tags = tagMapper.getTagByBlogId(blog.getBlogid());
        blog.setTags(tags.size() > 0 ? tags : null);
        asyncService.updatebloghits(blogid);//异步更新阅读次数
        return blog;
    }

    @Override
    public Tag getTagByTid(Integer t_id) {
        return tagMapper.selectByPrimaryKey(t_id);
    }

    @Override
    public List<Blog> getBlogByTagId(Integer tId) {
        List<Blog> blogs = blogMapper.getBlogByTagId(tId);
        for (Blog blog : blogs) {
            blog.setCategory(categoryMapper.selectByPrimaryKey(blog.getCategoryid()));
        }
        return blogs;
    }

    @Override
    public List<Blog> getBanner() {
        List<Blog> blogs = blogMapper.getBanner();
        return blogs;
    }

    @Override
    public List<Blog> getByHits() {
        List<Blog> blogs = blogMapper.getHits();
        for (Blog blog : blogs) {
            blog.setCategory(categoryMapper.selectByPrimaryKey(blog.getCategoryid()));
        }
        return blogs;
    }

    @Override
    public List<Blog> getLife() {
        List<Blog> blogs = blogMapper.getLife();
        for (Blog blog : blogs) {
            blog.setCategory(categoryMapper.selectByPrimaryKey(blog.getCategoryid()));
        }
        return blogs;
    }

    @Override
    public List<Blog> getAllTechBlog() {
        List<Blog> blogs = blogMapper.getAllTechBlog();
        for (Blog blog : blogs) {
            blog.setCategory(categoryMapper.selectByPrimaryKey(blog.getCategoryid()));
        }
        return blogs;
    }

    /**
     * 根据lucene搜索blog
     *
     * @param pageStart
     * @param keyword
     * @param pagehits
     * @return
     */
    @Override
    public List<Blog> getLuceneBlog(Integer pageStart, String keyword, Integer pagehits) {
        List<Blog> blogs = new ArrayList<>();
        try {
            blogs = blogIndex.searchBlog(pageStart, keyword, pagehits);
            for (Blog blog : blogs) {
                blog.setCategory(categoryMapper.selectByPrimaryKey(blog.getCategoryid()));
                blog.setTags(tagMapper.getTagByBlogId(blog.getBlogid()));
            }
        } catch (Exception e) {
            logger.error("搜索錯誤", e);
        }
        return blogs;
    }

    /**
     * ajax简历索引
     */
    @Override
    public void ajaxbuild() {
        try {
            Directory dir = FSDirectory.open(Paths.get("autocomplete"));
            SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
            AnalyzingInfixSuggester suggester = new AnalyzingInfixSuggester(dir, analyzer);
            //创建Blog测试数据
            List<Blog> blogs = blogMapper.getAllBlog();
            suggester.build(new BlogIterator(blogs.iterator()));
        } catch (IOException e) {
            System.err.println("Error!");
        }
    }

    /**
     * 根据关键词查找
     *
     * @param keyword
     * @return
     */
    @Override
    public Set<String> ajaxsearch(String keyword) {
        try {
            Directory dir = FSDirectory.open(Paths.get("autocomplete"));
            SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
            AnalyzingInfixSuggester suggester = new AnalyzingInfixSuggester(dir, analyzer);
            List<String> list = lookup(suggester, keyword);
            list.sort(new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    if (o1.length() > o2.length()) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
            });
            Set<String> set = new LinkedHashSet<>();
            for (String string : list) {
                set.add(string);
            }
            ssubSet(set, 7);
            return set;
        } catch (IOException e) {
            System.err.println("Error!");
            return null;
        }
    }

    public static void main(String[] args) {
        try {
            Directory dir = FSDirectory.open(Paths.get("autocomplete"));
            RAMDirectory indexDir = new RAMDirectory();
            SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
            AnalyzingInfixSuggester suggester = new AnalyzingInfixSuggester(dir, analyzer);
            IBlogService blogService = new BlogServiceImpl();
            lookup(suggester, "jav");
//            new BlogServiceImpl().ajaxsearch("北京");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
