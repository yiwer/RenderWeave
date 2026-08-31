/* Intentionally no include guard or pragma once.  FreeType's ftinit.c includes
 * FT_CONFIG_MODULES_H twice with different FT_USE_MODULE definitions. */
FT_USE_MODULE(FT_Driver_ClassRec, tt_driver_class)
FT_USE_MODULE(FT_Driver_ClassRec, cff_driver_class)
FT_USE_MODULE(FT_Module_Class, sfnt_module_class)
FT_USE_MODULE(FT_Module_Class, psaux_module_class)
FT_USE_MODULE(FT_Module_Class, psnames_module_class)
FT_USE_MODULE(FT_Renderer_Class, ft_smooth_renderer_class)
