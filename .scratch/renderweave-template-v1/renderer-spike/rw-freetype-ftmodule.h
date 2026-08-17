#ifndef RENDERWEAVE_FREETYPE_FTMODULE_H_
#define RENDERWEAVE_FREETYPE_FTMODULE_H_

FT_USE_MODULE(FT_Driver_ClassRec, tt_driver_class)
FT_USE_MODULE(FT_Driver_ClassRec, cff_driver_class)
FT_USE_MODULE(FT_Module_Class, sfnt_module_class)
FT_USE_MODULE(FT_Module_Class, psaux_module_class)
FT_USE_MODULE(FT_Module_Class, psnames_module_class)
FT_USE_MODULE(FT_Renderer_Class, ft_smooth_renderer_class)

#endif  /* RENDERWEAVE_FREETYPE_FTMODULE_H_ */
