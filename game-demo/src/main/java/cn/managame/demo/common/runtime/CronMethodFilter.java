package cn.managame.demo.common.runtime;

import cn.managame.runtime.timer.Cron;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;

/** Startup-only discovery of classes declaring or inheriting a Cron method. */
public class CronMethodFilter implements TypeFilter {
    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory readers) throws IOException {
        if (reader.getAnnotationMetadata().hasAnnotatedMethods(Cron.class.getName())) return true;
        for (String type : reader.getClassMetadata().getInterfaceNames())
            if (match(readers.getMetadataReader(type), readers)) return true;
        String parent = reader.getClassMetadata().getSuperClassName();
        return parent != null && !parent.equals(Object.class.getName())
                && match(readers.getMetadataReader(parent), readers);
    }
}
