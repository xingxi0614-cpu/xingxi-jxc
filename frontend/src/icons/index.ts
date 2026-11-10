/**
 * Tabler 图标兼容层
 * 把项目里原本使用的 Element Plus 图标名映射到 Tabler 细线图标组件。
 * - 静态用法：import { Search, Plus } from '@/icons'
 * - 动态/全局用法：main.ts 用 epIconMap 按 EP 名全局注册，<component :is="'ShoppingCart'" /> 即渲染 Tabler 图标
 * Element Plus 控件内部自带的小图标（清除叉、下拉箭头等）仍由 element-plus 自身提供，不在此替换。
 */
import {
  IconTarget,
  IconChevronDown,
  IconChevronLeft,
  IconChevronRight,
  IconUserCircle,
  IconBell,
  IconBox,
  IconCalendar,
  IconCircleCheck,
  IconX,
  IconCoin,
  IconStack2,
  IconLink,
  IconCopy,
  IconChartBar,
  IconLayoutDashboard,
  IconChartLine,
  IconTrash,
  IconFileText,
  IconFileCheck,
  IconFiles,
  IconDownload,
  IconPencil,
  IconLayoutSidebarLeftExpand,
  IconLayoutSidebarLeftCollapse,
  IconMaximize,
  IconShoppingBag,
  IconLayoutGrid,
  IconCompass,
  IconHome,
  IconList,
  IconLock,
  IconClipboardText,
  IconMenu2,
  IconMoon,
  IconCash,
  IconDeviceDesktop,
  IconDots,
  IconBuilding,
  IconPlus,
  IconPrinter,
  IconSend,
  IconBook,
  IconRefresh,
  IconSearch,
  IconSettings,
  IconShare,
  IconBuildingStore,
  IconShoppingCart,
  IconStar,
  IconStarFilled,
  IconLogout,
  IconReceipt,
  IconClock,
  IconTool,
  IconUser,
  IconTruck,
  IconAlertTriangle,
  IconSun,
  IconAdjustments,
  IconReportAnalytics
} from '@tabler/icons-vue'

export const Aim = IconTarget
export const ArrowDown = IconChevronDown
export const ArrowLeft = IconChevronLeft
export const ArrowRight = IconChevronRight
export const Avatar = IconUserCircle
export const Bell = IconBell
export const Box = IconBox
export const Calendar = IconCalendar
export const CircleCheck = IconCircleCheck
export const Close = IconX
export const Coin = IconCoin
export const Collection = IconStack2
export const Connection = IconLink
export const CopyDocument = IconCopy
export const DataAnalysis = IconReportAnalytics
export const DataBoard = IconLayoutDashboard
export const DataLine = IconChartLine
export const Delete = IconTrash
export const Document = IconFileText
export const DocumentChecked = IconFileCheck
export const DocumentCopy = IconFiles
export const Download = IconDownload
export const EditPen = IconPencil
export const Expand = IconLayoutSidebarLeftExpand
export const Fold = IconLayoutSidebarLeftCollapse
export const FullScreen = IconMaximize
export const Goods = IconShoppingBag
export const Grid = IconLayoutGrid
export const Guide = IconCompass
export const Histogram = IconChartBar
export const House = IconHome
export const List = IconList
export const Lock = IconLock
export const Memo = IconClipboardText
export const Menu = IconMenu2
export const MoonNight = IconMoon
export const Money = IconCash
export const Monitor = IconDeviceDesktop
export const MoreFilled = IconDots
export const OfficeBuilding = IconBuilding
export const Plus = IconPlus
export const Printer = IconPrinter
export const Promotion = IconSend
export const Reading = IconBook
export const Refresh = IconRefresh
export const RefreshRight = IconRefresh
export const Search = IconSearch
export const Setting = IconSettings
export const Share = IconShare
export const Shop = IconBuildingStore
export const ShoppingCart = IconShoppingCart
export const Star = IconStar
export const StarFilled = IconStarFilled
export const SwitchButton = IconLogout
export const Tickets = IconReceipt
export const Timer = IconClock
export const Tools = IconTool
export const User = IconUser
export const Van = IconTruck
export const Warning = IconAlertTriangle
export const Sunny = IconSun
export const Compass = IconCompass
export const Operation = IconAdjustments

export const epIconMap: Record<string, unknown> = {
  Aim, ArrowDown, ArrowLeft, ArrowRight, Avatar, Bell, Box, Calendar, CircleCheck,
  Close, Coin, Collection, Connection, CopyDocument, DataAnalysis, DataBoard, DataLine,
  Delete, Document, DocumentChecked, DocumentCopy, Download, EditPen, Expand, Fold,
  FullScreen, Goods, Grid, Guide, Histogram, House, List, Lock, Memo, Menu, MoonNight,
  Money, Monitor, MoreFilled, OfficeBuilding, Plus, Printer, Promotion, Reading, Refresh,
  RefreshRight, Search, Setting, Share, Shop, ShoppingCart, Star, StarFilled, SwitchButton,
  Tickets, Timer, Tools, User, Van, Warning, Sunny, Compass, Operation
}
