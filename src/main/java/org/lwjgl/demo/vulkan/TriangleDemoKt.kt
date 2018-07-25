/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.demo.vulkan

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWKeyCallback
import org.lwjgl.glfw.GLFWWindowSizeCallback
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

import org.lwjgl.demo.opengl.util.DemoUtils.ioResourceToByteBuffer
import org.lwjgl.demo.vulkan.TriangleDemoKt.loadShader
import org.lwjgl.demo.vulkan.VKUtil.translateVulkanResult
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.*
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.vulkan.EXTDebugReport.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*

/**
 * Renders a simple triangle on a cornflower blue background on a GLFW window with Vulkan.
 *
 * @author Kai Burjack
 */
object TriangleDemoKt {

    private val validation = java.lang.Boolean.parseBoolean(System.getProperty("vulkan.validation", "false"))

    private val layers = arrayOf(memUTF8("VK_LAYER_LUNARG_standard_validation"))

    /**
     * Remove if added to spec.
     */
    private val VK_FLAGS_NONE = 0

    /**
     * This is just -1L, but it is nicer as a symbolic constant.
     */
    private val UINT64_MAX = -0x1L

    /*
     * All resources that must be reallocated on window resize.
     */
    private var swapchain: Swapchain? = null
    private var framebuffers: LongArray? = null
    private var width: Int = 0
    private var height: Int = 0
    private var renderCommandBuffers: Array<VkCommandBuffer?>? = null

    /**
     * Create a Vulkan instance using LWJGL 3.
     *
     * @return the VkInstance handle
     */
    private fun createInstance(requiredExtensions: PointerBuffer): VkInstance {
        val appInfo = VkApplicationInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(memUTF8("GLFW Vulkan Demo"))
                .pEngineName(memUTF8(""))
                .apiVersion(VK_MAKE_VERSION(1, 0, 2))
        val ppEnabledExtensionNames = memAllocPointer(requiredExtensions.remaining() + 1)
        ppEnabledExtensionNames.put(requiredExtensions)
        val VK_EXT_DEBUG_REPORT_EXTENSION = memUTF8(VK_EXT_DEBUG_REPORT_EXTENSION_NAME)
        ppEnabledExtensionNames.put(VK_EXT_DEBUG_REPORT_EXTENSION)
        ppEnabledExtensionNames.flip()
        val ppEnabledLayerNames = memAllocPointer(layers.size)
        var i = 0
        while (validation && i < layers.size) {
            ppEnabledLayerNames.put(layers[i])
            i++
        }
        ppEnabledLayerNames.flip()
        val pCreateInfo = VkInstanceCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pNext(NULL)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(ppEnabledExtensionNames)
                .ppEnabledLayerNames(ppEnabledLayerNames)
        val pInstance = memAllocPointer(1)
        val err = vkCreateInstance(pCreateInfo, null, pInstance)
        val instance = pInstance.get(0)
        memFree(pInstance)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create VkInstance: " + translateVulkanResult(err))
        }
        val ret = VkInstance(instance, pCreateInfo)
        pCreateInfo.free()
        memFree(ppEnabledLayerNames)
        memFree(VK_EXT_DEBUG_REPORT_EXTENSION)
        memFree(ppEnabledExtensionNames)
        memFree(appInfo.pApplicationName())
        memFree(appInfo.pEngineName())
        appInfo.free()
        return ret
    }

    private fun setupDebugging(instance: VkInstance, flags: Int, callback: VkDebugReportCallbackEXT): Long {
        val dbgCreateInfo = VkDebugReportCallbackCreateInfoEXT.calloc()
                .sType(VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT)
                .pNext(NULL)
                .pfnCallback(callback)
                .pUserData(NULL)
                .flags(flags)
        val pCallback = memAllocLong(1)
        val err = vkCreateDebugReportCallbackEXT(instance, dbgCreateInfo, null, pCallback)
        val callbackHandle = pCallback.get(0)
        memFree(pCallback)
        dbgCreateInfo.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create VkInstance: " + translateVulkanResult(err))
        }
        return callbackHandle
    }

    private fun getFirstPhysicalDevice(instance: VkInstance): VkPhysicalDevice {
        val pPhysicalDeviceCount = memAllocInt(1)
        var err = vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, null)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get number of physical devices: " + translateVulkanResult(err))
        }
        val pPhysicalDevices = memAllocPointer(pPhysicalDeviceCount.get(0))
        err = vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices)
        val physicalDevice = pPhysicalDevices.get(0)
        memFree(pPhysicalDeviceCount)
        memFree(pPhysicalDevices)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get physical devices: " + translateVulkanResult(err))
        }
        return VkPhysicalDevice(physicalDevice, instance)
    }

    private class DeviceAndGraphicsQueueFamily {
        internal var device: VkDevice? = null
        internal var queueFamilyIndex: Int = 0
        internal var memoryProperties: VkPhysicalDeviceMemoryProperties? = null
    }

    private fun createDeviceAndGetGraphicsQueueFamily(physicalDevice: VkPhysicalDevice): DeviceAndGraphicsQueueFamily {
        val pQueueFamilyPropertyCount = memAllocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null)
        val queueCount = pQueueFamilyPropertyCount.get(0)
        val queueProps = VkQueueFamilyProperties.calloc(queueCount)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, queueProps)
        memFree(pQueueFamilyPropertyCount)
        var graphicsQueueFamilyIndex: Int
        graphicsQueueFamilyIndex = 0
        while (graphicsQueueFamilyIndex < queueCount) {
            if (queueProps.get(graphicsQueueFamilyIndex).queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0)
                break
            graphicsQueueFamilyIndex++
        }
        queueProps.free()
        val pQueuePriorities = memAllocFloat(1).put(0.0f)
        pQueuePriorities.flip()
        val queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamilyIndex)
                .pQueuePriorities(pQueuePriorities)

        val extensions = memAllocPointer(1)
        val VK_KHR_SWAPCHAIN_EXTENSION = memUTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
        extensions.put(VK_KHR_SWAPCHAIN_EXTENSION)
        extensions.flip()
        val ppEnabledLayerNames = memAllocPointer(layers.size)
        var i = 0
        while (validation && i < layers.size) {
            ppEnabledLayerNames.put(layers[i])
            i++
        }
        ppEnabledLayerNames.flip()

        val deviceCreateInfo = VkDeviceCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(NULL)
                .pQueueCreateInfos(queueCreateInfo)
                .ppEnabledExtensionNames(extensions)
                .ppEnabledLayerNames(ppEnabledLayerNames)

        val pDevice = memAllocPointer(1)
        val err = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice)
        val device = pDevice.get(0)
        memFree(pDevice)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create device: " + translateVulkanResult(err))
        }

        val memoryProperties = VkPhysicalDeviceMemoryProperties.calloc()
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties)

        val ret = DeviceAndGraphicsQueueFamily()
        ret.device = VkDevice(device, physicalDevice, deviceCreateInfo)
        ret.queueFamilyIndex = graphicsQueueFamilyIndex
        ret.memoryProperties = memoryProperties

        deviceCreateInfo.free()
        memFree(ppEnabledLayerNames)
        memFree(VK_KHR_SWAPCHAIN_EXTENSION)
        memFree(extensions)
        memFree(pQueuePriorities)
        return ret
    }

    private class ColorFormatAndSpace {
        internal var colorFormat: Int = 0
        internal var colorSpace: Int = 0
    }

    private fun getColorFormatAndSpace(physicalDevice: VkPhysicalDevice, surface: Long): ColorFormatAndSpace {
        val pQueueFamilyPropertyCount = memAllocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null)
        val queueCount = pQueueFamilyPropertyCount.get(0)
        val queueProps = VkQueueFamilyProperties.calloc(queueCount)
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, queueProps)
        memFree(pQueueFamilyPropertyCount)

        // Iterate over each queue to learn whether it supports presenting:
        val supportsPresent = memAllocInt(queueCount)
        for (i in 0 until queueCount) {
            supportsPresent.position(i)
            val err = vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, surface, supportsPresent)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to physical device surface support: " + translateVulkanResult(err))
            }
        }

        // Search for a graphics and a present queue in the array of queue families, try to find one that supports both
        var graphicsQueueNodeIndex = Integer.MAX_VALUE
        var presentQueueNodeIndex = Integer.MAX_VALUE
        for (i in 0 until queueCount) {
            if (queueProps.get(i).queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) {
                if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
                    graphicsQueueNodeIndex = i
                }
                if (supportsPresent.get(i) == VK_TRUE) {
                    graphicsQueueNodeIndex = i
                    presentQueueNodeIndex = i
                    break
                }
            }
        }
        queueProps.free()
        if (presentQueueNodeIndex == Integer.MAX_VALUE) {
            // If there's no queue that supports both present and graphics try to find a separate present queue
            for (i in 0 until queueCount) {
                if (supportsPresent.get(i) == VK_TRUE) {
                    presentQueueNodeIndex = i
                    break
                }
            }
        }
        memFree(supportsPresent)

        // Generate error if could not find both a graphics and a present queue
        if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
            throw AssertionError("No graphics queue found")
        }
        if (presentQueueNodeIndex == Integer.MAX_VALUE) {
            throw AssertionError("No presentation queue found")
        }
        if (graphicsQueueNodeIndex != presentQueueNodeIndex) {
            throw AssertionError("Presentation queue != graphics queue")
        }

        // Get list of supported formats
        val pFormatCount = memAllocInt(1)
        var err = vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFormatCount, null)
        val formatCount = pFormatCount.get(0)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to query number of physical device surface formats: " + translateVulkanResult(err))
        }

        val surfFormats = VkSurfaceFormatKHR.calloc(formatCount)
        err = vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFormatCount, surfFormats)
        memFree(pFormatCount)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to query physical device surface formats: " + translateVulkanResult(err))
        }

        val colorFormat: Int
        if (formatCount == 1 && surfFormats.get(0).format() == VK_FORMAT_UNDEFINED) {
            colorFormat = VK_FORMAT_B8G8R8A8_UNORM
        } else {
            colorFormat = surfFormats.get(0).format()
        }
        val colorSpace = surfFormats.get(0).colorSpace()
        surfFormats.free()

        val ret = ColorFormatAndSpace()
        ret.colorFormat = colorFormat
        ret.colorSpace = colorSpace
        return ret
    }

    private fun createCommandPool(device: VkDevice?, queueNodeIndex: Int): Long {
        val cmdPoolInfo = VkCommandPoolCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(queueNodeIndex)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
        val pCmdPool = memAllocLong(1)
        val err = vkCreateCommandPool(device!!, cmdPoolInfo, null, pCmdPool)
        val commandPool = pCmdPool.get(0)
        cmdPoolInfo.free()
        memFree(pCmdPool)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create command pool: " + translateVulkanResult(err))
        }
        return commandPool
    }

    private fun createDeviceQueue(device: VkDevice?, queueFamilyIndex: Int): VkQueue {
        val pQueue = memAllocPointer(1)
        vkGetDeviceQueue(device!!, queueFamilyIndex, 0, pQueue)
        val queue = pQueue.get(0)
        memFree(pQueue)
        return VkQueue(queue, device)
    }

    private fun createCommandBuffer(device: VkDevice?, commandPool: Long): VkCommandBuffer {
        val cmdBufAllocateInfo = VkCommandBufferAllocateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
        val pCommandBuffer = memAllocPointer(1)
        val err = vkAllocateCommandBuffers(device!!, cmdBufAllocateInfo, pCommandBuffer)
        cmdBufAllocateInfo.free()
        val commandBuffer = pCommandBuffer.get(0)
        memFree(pCommandBuffer)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to allocate command buffer: " + translateVulkanResult(err))
        }
        return VkCommandBuffer(commandBuffer, device)
    }

    private fun imageBarrier(cmdbuffer: VkCommandBuffer, image: Long, aspectMask: Int, oldImageLayout: Int, srcAccess: Int, newImageLayout: Int, dstAccess: Int) {
        // Create an image barrier object
        val imageMemoryBarrier = VkImageMemoryBarrier.calloc(1)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .pNext(NULL)
                .oldLayout(oldImageLayout)
                .srcAccessMask(srcAccess)
                .newLayout(newImageLayout)
                .dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
        imageMemoryBarrier.subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .layerCount(1)

        // Put barrier on top
        val srcStageFlags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
        val destStageFlags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT

        // Put barrier inside setup command buffer
        vkCmdPipelineBarrier(cmdbuffer, srcStageFlags, destStageFlags, VK_FLAGS_NONE, null, null, // no buffer memory barriers
                imageMemoryBarrier)// no memory barriers
        // one image memory barrier
        imageMemoryBarrier.free()
    }

    private class Swapchain {
        internal var swapchainHandle: Long = 0
        internal var images: LongArray? = null
        internal var imageViews: LongArray? = null
    }

    private fun createSwapChain(device: VkDevice?, physicalDevice: VkPhysicalDevice, surface: Long, oldSwapChain: Long, commandBuffer: VkCommandBuffer, newWidth: Int,
                                newHeight: Int, colorFormat: Int, colorSpace: Int): Swapchain {
        var err: Int
        // Get physical device surface properties and formats
        val surfCaps = VkSurfaceCapabilitiesKHR.calloc()
        err = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, surfCaps)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get physical device surface capabilities: " + translateVulkanResult(err))
        }

        val pPresentModeCount = memAllocInt(1)
        err = vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, null)
        val presentModeCount = pPresentModeCount.get(0)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get number of physical device surface presentation modes: " + translateVulkanResult(err))
        }

        val pPresentModes = memAllocInt(presentModeCount)
        err = vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, pPresentModes)
        memFree(pPresentModeCount)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get physical device surface presentation modes: " + translateVulkanResult(err))
        }

        // Try to use mailbox mode. Low latency and non-tearing
        var swapchainPresentMode = VK_PRESENT_MODE_FIFO_KHR
        for (i in 0 until presentModeCount) {
            if (pPresentModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                swapchainPresentMode = VK_PRESENT_MODE_MAILBOX_KHR
                break
            }
            if (swapchainPresentMode != VK_PRESENT_MODE_MAILBOX_KHR && pPresentModes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) {
                swapchainPresentMode = VK_PRESENT_MODE_IMMEDIATE_KHR
            }
        }
        memFree(pPresentModes)

        // Determine the number of images
        var desiredNumberOfSwapchainImages = surfCaps.minImageCount() + 1
        if (surfCaps.maxImageCount() > 0 && desiredNumberOfSwapchainImages > surfCaps.maxImageCount()) {
            desiredNumberOfSwapchainImages = surfCaps.maxImageCount()
        }

        val currentExtent = surfCaps.currentExtent()
        val currentWidth = currentExtent.width()
        val currentHeight = currentExtent.height()
        if (currentWidth != -1 && currentHeight != -1) {
            width = currentWidth
            height = currentHeight
        } else {
            width = newWidth
            height = newHeight
        }

        val preTransform: Int
        if (surfCaps.supportedTransforms() and VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR != 0) {
            preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
        } else {
            preTransform = surfCaps.currentTransform()
        }
        surfCaps.free()

        val swapchainCI = VkSwapchainCreateInfoKHR.calloc()
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .pNext(NULL)
                .surface(surface)
                .minImageCount(desiredNumberOfSwapchainImages)
                .imageFormat(colorFormat)
                .imageColorSpace(colorSpace)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .preTransform(preTransform)
                .imageArrayLayers(1)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .pQueueFamilyIndices(null)
                .presentMode(swapchainPresentMode)
                .oldSwapchain(oldSwapChain)
                .clipped(true)
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        swapchainCI.imageExtent()
                .width(width)
                .height(height)
        val pSwapChain = memAllocLong(1)
        err = vkCreateSwapchainKHR(device!!, swapchainCI, null, pSwapChain)
        swapchainCI.free()
        val swapChain = pSwapChain.get(0)
        memFree(pSwapChain)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create swap chain: " + translateVulkanResult(err))
        }

        // If we just re-created an existing swapchain, we should destroy the old swapchain at this point.
        // Note: destroying the swapchain also cleans up all its associated presentable images once the platform is done with them.
        if (oldSwapChain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, oldSwapChain, null)
        }

        val pImageCount = memAllocInt(1)
        err = vkGetSwapchainImagesKHR(device, swapChain, pImageCount, null)
        val imageCount = pImageCount.get(0)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get number of swapchain images: " + translateVulkanResult(err))
        }

        val pSwapchainImages = memAllocLong(imageCount)
        err = vkGetSwapchainImagesKHR(device, swapChain, pImageCount, pSwapchainImages)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to get swapchain images: " + translateVulkanResult(err))
        }
        memFree(pImageCount)

        val images = LongArray(imageCount)
        val imageViews = LongArray(imageCount)
        val pBufferView = memAllocLong(1)
        val colorAttachmentView = VkImageViewCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .pNext(NULL)
                .format(colorFormat)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .flags(VK_FLAGS_NONE)
        colorAttachmentView.components()
                .r(VK_COMPONENT_SWIZZLE_R)
                .g(VK_COMPONENT_SWIZZLE_G)
                .b(VK_COMPONENT_SWIZZLE_B)
                .a(VK_COMPONENT_SWIZZLE_A)
        colorAttachmentView.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
        for (i in 0 until imageCount) {
            images[i] = pSwapchainImages.get(i)
            // Bring the image from an UNDEFINED state to the VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT state
            imageBarrier(commandBuffer, images[i], VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, 0,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
            colorAttachmentView.image(images[i])
            err = vkCreateImageView(device, colorAttachmentView, null, pBufferView)
            imageViews[i] = pBufferView.get(0)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to create image view: " + translateVulkanResult(err))
            }
        }
        colorAttachmentView.free()
        memFree(pBufferView)
        memFree(pSwapchainImages)

        val ret = Swapchain()
        ret.images = images
        ret.imageViews = imageViews
        ret.swapchainHandle = swapChain
        return ret
    }

    private fun createRenderPass(device: VkDevice?, colorFormat: Int): Long {
        val attachments = VkAttachmentDescription.calloc(1)
                .format(colorFormat)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

        val colorReference = VkAttachmentReference.calloc(1)
                .attachment(0)
                .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

        val subpass = VkSubpassDescription.calloc(1)
                .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                .flags(VK_FLAGS_NONE)
                .pInputAttachments(null)
                .colorAttachmentCount(colorReference.remaining())
                .pColorAttachments(colorReference) // <- only color attachment
                .pResolveAttachments(null)
                .pDepthStencilAttachment(null)
                .pPreserveAttachments(null)

        val renderPassInfo = VkRenderPassCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                .pNext(NULL)
                .pAttachments(attachments)
                .pSubpasses(subpass)
                .pDependencies(null)

        val pRenderPass = memAllocLong(1)
        val err = vkCreateRenderPass(device!!, renderPassInfo, null, pRenderPass)
        val renderPass = pRenderPass.get(0)
        memFree(pRenderPass)
        renderPassInfo.free()
        colorReference.free()
        subpass.free()
        attachments.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create clear render pass: " + translateVulkanResult(err))
        }
        return renderPass
    }

    private fun createFramebuffers(device: VkDevice?, swapchain: Swapchain, renderPass: Long, width: Int, height: Int): LongArray {
        val attachments = memAllocLong(1)
        val fci = VkFramebufferCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                .pAttachments(attachments)
                .flags(VK_FLAGS_NONE)
                .height(height)
                .width(width)
                .layers(1)
                .pNext(NULL)
                .renderPass(renderPass)
        // Create a framebuffer for each swapchain image
        val framebuffers = LongArray(swapchain.images!!.size)
        val pFramebuffer = memAllocLong(1)
        for (i in swapchain.images!!.indices) {
            attachments.put(0, swapchain.imageViews!![i])
            val err = vkCreateFramebuffer(device!!, fci, null, pFramebuffer)
            val framebuffer = pFramebuffer.get(0)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to create framebuffer: " + translateVulkanResult(err))
            }
            framebuffers[i] = framebuffer
        }
        memFree(attachments)
        memFree(pFramebuffer)
        fci.free()
        return framebuffers
    }

    private fun submitCommandBuffer(queue: VkQueue, commandBuffer: VkCommandBuffer?) {
        if (commandBuffer == null || commandBuffer.address() == NULL)
            return
        val submitInfo = VkSubmitInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
        val pCommandBuffers = memAllocPointer(1)
                .put(commandBuffer)
                .flip()
        submitInfo.pCommandBuffers(pCommandBuffers)
        val err = vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE)
        memFree(pCommandBuffers)
        submitInfo.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to submit command buffer: " + translateVulkanResult(err))
        }
    }

    @Throws(IOException::class)
    private fun loadShader(classPath: String, device: VkDevice?): Long {
        val shaderCode = ioResourceToByteBuffer(classPath, 1024)
        val err: Int
        val moduleCreateInfo = VkShaderModuleCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pNext(NULL)
                .pCode(shaderCode)
                .flags(0)
        val pShaderModule = memAllocLong(1)
        err = vkCreateShaderModule(device!!, moduleCreateInfo, null, pShaderModule)
        val shaderModule = pShaderModule.get(0)
        memFree(pShaderModule)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create shader module: " + translateVulkanResult(err))
        }
        return shaderModule
    }

    @Throws(IOException::class)
    private fun loadShader(device: VkDevice?, classPath: String, stage: Int): VkPipelineShaderStageCreateInfo {
        return VkPipelineShaderStageCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(stage)
                .module(loadShader(classPath, device))
                .pName(memUTF8("main"))
    }

    private fun getMemoryType(deviceMemoryProperties: VkPhysicalDeviceMemoryProperties?, typeBits: Int, properties: Int, typeIndex: IntBuffer): Boolean {
        var bits = typeBits
        for (i in 0..31) {
            if (bits and 1 == 1) {
                if (deviceMemoryProperties!!.memoryTypes(i).propertyFlags() and properties == properties) {
                    typeIndex.put(0, i)
                    return true
                }
            }
            bits = bits shr 1
        }
        return false
    }

    private class Vertices {
        internal var verticesBuf: Long = 0
        internal var createInfo: VkPipelineVertexInputStateCreateInfo? = null
    }

    private fun createVertices(deviceMemoryProperties: VkPhysicalDeviceMemoryProperties?, device: VkDevice?): Vertices {
        val vertexBuffer = memAlloc(3 * 2 * 4)
        val fb = vertexBuffer.asFloatBuffer()
        // The triangle will showup upside-down, because Vulkan does not do proper viewport transformation to
        // account for inverted Y axis between the window coordinate system and clip space/NDC
        fb.put(-0.5f).put(-0.5f)
        fb.put(0.5f).put(-0.5f)
        fb.put(0.0f).put(0.5f)

        val memAlloc = VkMemoryAllocateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(NULL)
                .allocationSize(0)
                .memoryTypeIndex(0)
        val memReqs = VkMemoryRequirements.calloc()

        var err: Int

        // Generate vertex buffer
        //  Setup
        val bufInfo = VkBufferCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .pNext(NULL)
                .size(vertexBuffer.remaining().toLong())
                .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                .flags(0)
        val pBuffer = memAllocLong(1)
        err = vkCreateBuffer(device!!, bufInfo, null, pBuffer)
        val verticesBuf = pBuffer.get(0)
        memFree(pBuffer)
        bufInfo.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create vertex buffer: " + translateVulkanResult(err))
        }

        vkGetBufferMemoryRequirements(device, verticesBuf, memReqs)
        memAlloc.allocationSize(memReqs.size())
        val memoryTypeIndex = memAllocInt(1)
        getMemoryType(deviceMemoryProperties, memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, memoryTypeIndex)
        memAlloc.memoryTypeIndex(memoryTypeIndex.get(0))
        memFree(memoryTypeIndex)
        memReqs.free()

        val pMemory = memAllocLong(1)
        err = vkAllocateMemory(device, memAlloc, null, pMemory)
        val verticesMem = pMemory.get(0)
        memFree(pMemory)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to allocate vertex memory: " + translateVulkanResult(err))
        }

        val pData = memAllocPointer(1)
        err = vkMapMemory(device, verticesMem, 0, memAlloc.allocationSize(), 0, pData)
        memAlloc.free()
        val data = pData.get(0)
        memFree(pData)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to map vertex memory: " + translateVulkanResult(err))
        }

        MemoryUtil.memCopy(memAddress(vertexBuffer), data, vertexBuffer.remaining().toLong())
        memFree(vertexBuffer)
        vkUnmapMemory(device, verticesMem)
        err = vkBindBufferMemory(device, verticesBuf, verticesMem, 0)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to bind memory to vertex buffer: " + translateVulkanResult(err))
        }

        // Binding description
        val bindingDescriptor = VkVertexInputBindingDescription.calloc(1)
                .binding(0) // <- we bind our vertex buffer to point 0
                .stride(2 * 4)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

        // Attribute descriptions
        // Describes memory layout and shader attribute locations
        val attributeDescriptions = VkVertexInputAttributeDescription.calloc(1)
        // Location 0 : Position
        attributeDescriptions.get(0)
                .binding(0) // <- binding point used in the VkVertexInputBindingDescription
                .location(0) // <- location in the shader's attribute layout (inside the shader source)
                .format(VK_FORMAT_R32G32_SFLOAT)
                .offset(0)

        // Assign to vertex buffer
        val vi = VkPipelineVertexInputStateCreateInfo.calloc()
        vi.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
        vi.pNext(NULL)
        vi.pVertexBindingDescriptions(bindingDescriptor)
        vi.pVertexAttributeDescriptions(attributeDescriptions)

        val ret = Vertices()
        ret.createInfo = vi
        ret.verticesBuf = verticesBuf
        return ret
    }

    @Throws(IOException::class)
    private fun createPipeline(device: VkDevice?, renderPass: Long, vi: VkPipelineVertexInputStateCreateInfo?): Long {
        var err: Int
        // Vertex input state
        // Describes the topoloy used with this pipeline
        val inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)

        // Rasterization state
        val rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .depthBiasEnable(false)

        // Color blend state
        // Describes blend modes and color masks
        val colorWriteMask = VkPipelineColorBlendAttachmentState.calloc(1)
                .blendEnable(false)
                .colorWriteMask(0xF) // <- RGBA
        val colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(colorWriteMask)

        // Viewport state
        val viewportState = VkPipelineViewportStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1) // <- one viewport
                .scissorCount(1) // <- one scissor rectangle

        // Enable dynamic states
        // Describes the dynamic states to be used with this pipeline
        // Dynamic states can be set even after the pipeline has been created
        // So there is no need to create new pipelines just for changing
        // a viewport's dimensions or a scissor box
        val pDynamicStates = memAllocInt(2)
        pDynamicStates.put(VK_DYNAMIC_STATE_VIEWPORT).put(VK_DYNAMIC_STATE_SCISSOR).flip()
        val dynamicState = VkPipelineDynamicStateCreateInfo.calloc()
                // The dynamic state properties themselves are stored in the command buffer
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(pDynamicStates)

        // Depth and stencil state
        // Describes depth and stenctil test and compare ops
        val depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc()
                // No depth test/write and no stencil used
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(false)
                .depthWriteEnable(false)
                .depthCompareOp(VK_COMPARE_OP_ALWAYS)
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false)
        depthStencilState.back()
                .failOp(VK_STENCIL_OP_KEEP)
                .passOp(VK_STENCIL_OP_KEEP)
                .compareOp(VK_COMPARE_OP_ALWAYS)
        depthStencilState.front(depthStencilState.back())

        // Multi sampling state
        // No multi sampling used in this example
        val multisampleState = VkPipelineMultisampleStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .pSampleMask(null)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

        // Load shaders
        val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2)
        shaderStages.get(0).set(loadShader(device, "triangle.vert.spv", VK_SHADER_STAGE_VERTEX_BIT))
        shaderStages.get(1).set(loadShader(device, "triangle.frag.spv", VK_SHADER_STAGE_FRAGMENT_BIT))

        // Create the pipeline layout that is used to generate the rendering pipelines that
        // are based on this descriptor set layout
        val pPipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pNext(NULL)
                .pSetLayouts(null)

        val pPipelineLayout = memAllocLong(1)
        err = vkCreatePipelineLayout(device!!, pPipelineLayoutCreateInfo, null, pPipelineLayout)
        val layout = pPipelineLayout.get(0)
        memFree(pPipelineLayout)
        pPipelineLayoutCreateInfo.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create pipeline layout: " + translateVulkanResult(err))
        }

        // Assign states
        val pipelineCreateInfo = VkGraphicsPipelineCreateInfo.calloc(1)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .layout(layout) // <- the layout used for this pipeline (NEEDS TO BE SET! even though it is basically empty)
                .renderPass(renderPass) // <- renderpass this pipeline is attached to
                .pVertexInputState(vi!!)
                .pInputAssemblyState(inputAssemblyState)
                .pRasterizationState(rasterizationState)
                .pColorBlendState(colorBlendState)
                .pMultisampleState(multisampleState)
                .pViewportState(viewportState)
                .pDepthStencilState(depthStencilState)
                .pStages(shaderStages)
                .pDynamicState(dynamicState)

        // Create rendering pipeline
        val pPipelines = memAllocLong(1)
        err = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineCreateInfo, null, pPipelines)
        val pipeline = pPipelines.get(0)
        shaderStages.free()
        multisampleState.free()
        depthStencilState.free()
        dynamicState.free()
        memFree(pDynamicStates)
        viewportState.free()
        colorBlendState.free()
        colorWriteMask.free()
        rasterizationState.free()
        inputAssemblyState.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create pipeline: " + translateVulkanResult(err))
        }
        return pipeline
    }

    private fun createRenderCommandBuffers(device: VkDevice?, commandPool: Long, framebuffers: LongArray, renderPass: Long, width: Int, height: Int,
                                           pipeline: Long, verticesBuf: Long): Array<VkCommandBuffer?> {
        // Create the render command buffers (one command buffer per framebuffer image)
        val cmdBufAllocateInfo = VkCommandBufferAllocateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(framebuffers.size)
        val pCommandBuffer = memAllocPointer(framebuffers.size)
        var err = vkAllocateCommandBuffers(device!!, cmdBufAllocateInfo, pCommandBuffer)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to allocate render command buffer: " + translateVulkanResult(err))
        }
        val renderCommandBuffers = arrayOfNulls<VkCommandBuffer>(framebuffers.size)
        for (i in framebuffers.indices) {
            renderCommandBuffers[i] = VkCommandBuffer(pCommandBuffer.get(i), device)
        }
        memFree(pCommandBuffer)
        cmdBufAllocateInfo.free()

        // Create the command buffer begin structure
        val cmdBufInfo = VkCommandBufferBeginInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .pNext(NULL)

        // Specify clear color (cornflower blue)
        val clearValues = VkClearValue.calloc(1)
        clearValues.color()
                .float32(0, 100 / 255.0f)
                .float32(1, 149 / 255.0f)
                .float32(2, 237 / 255.0f)
                .float32(3, 1.0f)

        // Specify everything to begin a render pass
        val renderPassBeginInfo = VkRenderPassBeginInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .pNext(NULL)
                .renderPass(renderPass)
                .pClearValues(clearValues)
        val renderArea = renderPassBeginInfo.renderArea()
        renderArea.offset().set(0, 0)
        renderArea.extent().set(width, height)

        for (i in renderCommandBuffers.indices) {
            // Set target frame buffer
            renderPassBeginInfo.framebuffer(framebuffers[i])

            err = vkBeginCommandBuffer(renderCommandBuffers[i], cmdBufInfo)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to begin render command buffer: " + translateVulkanResult(err))
            }

            vkCmdBeginRenderPass(renderCommandBuffers[i], renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE)

            // Update dynamic viewport state
            val viewport = VkViewport.calloc(1)
                    .height(height.toFloat())
                    .width(width.toFloat())
                    .minDepth(0.0f)
                    .maxDepth(1.0f)
            vkCmdSetViewport(renderCommandBuffers[i], 0, viewport)
            viewport.free()

            // Update dynamic scissor state
            val scissor = VkRect2D.calloc(1)
            scissor.extent().set(width, height)
            scissor.offset().set(0, 0)
            vkCmdSetScissor(renderCommandBuffers[i], 0, scissor)
            scissor.free()

            // Bind the rendering pipeline (including the shaders)
            vkCmdBindPipeline(renderCommandBuffers[i], VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

            // Bind triangle vertices
            val offsets = memAllocLong(1)
            offsets.put(0, 0L)
            val pBuffers = memAllocLong(1)
            pBuffers.put(0, verticesBuf)
            vkCmdBindVertexBuffers(renderCommandBuffers[i], 0, pBuffers, offsets)
            memFree(pBuffers)
            memFree(offsets)

            // Draw triangle
            vkCmdDraw(renderCommandBuffers[i], 3, 1, 0, 0)

            vkCmdEndRenderPass(renderCommandBuffers[i])

            // Add a present memory barrier to the end of the command buffer
            // This will transform the frame buffer color attachment to a
            // new layout for presenting it to the windowing system integration
            val prePresentBarrier = createPrePresentBarrier(swapchain!!.images!![i])
            vkCmdPipelineBarrier(renderCommandBuffers[i],
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_FLAGS_NONE, null, null, // No buffer memory barriers
                    prePresentBarrier)// No memory barriers
            // One image memory barrier
            prePresentBarrier.free()

            err = vkEndCommandBuffer(renderCommandBuffers[i])
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to begin render command buffer: " + translateVulkanResult(err))
            }
        }
        renderPassBeginInfo.free()
        clearValues.free()
        cmdBufInfo.free()
        return renderCommandBuffers
    }

    private fun createPrePresentBarrier(presentImage: Long): VkImageMemoryBarrier.Buffer {
        val imageMemoryBarrier = VkImageMemoryBarrier.calloc(1)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .pNext(NULL)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .dstAccessMask(0)
                .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        imageMemoryBarrier.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
        imageMemoryBarrier.image(presentImage)
        return imageMemoryBarrier
    }

    private fun createPostPresentBarrier(presentImage: Long): VkImageMemoryBarrier.Buffer {
        val imageMemoryBarrier = VkImageMemoryBarrier.calloc(1)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .pNext(NULL)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        imageMemoryBarrier.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)
        imageMemoryBarrier.image(presentImage)
        return imageMemoryBarrier
    }

    private fun submitPostPresentBarrier(image: Long, commandBuffer: VkCommandBuffer, queue: VkQueue) {
        val cmdBufInfo = VkCommandBufferBeginInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .pNext(NULL)
        var err = vkBeginCommandBuffer(commandBuffer, cmdBufInfo)
        cmdBufInfo.free()
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to begin command buffer: " + translateVulkanResult(err))
        }

        val postPresentBarrier = createPostPresentBarrier(image)
        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_FLAGS_NONE, null, null, // No buffer barriers,
                postPresentBarrier)// No memory barriers,
        // one image barrier
        postPresentBarrier.free()

        err = vkEndCommandBuffer(commandBuffer)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to wait for idle queue: " + translateVulkanResult(err))
        }

        // Submit the command buffer
        submitCommandBuffer(queue, commandBuffer)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        if (!glfwInit()) {
            throw RuntimeException("Failed to initialize GLFW")
        }
        if (!glfwVulkanSupported()) {
            throw AssertionError("GLFW failed to find the Vulkan loader")
        }

        /* Look for instance extensions */
        val requiredExtensions = glfwGetRequiredInstanceExtensions()
                ?: throw AssertionError("Failed to find list of required Vulkan extensions")

        // Create the Vulkan instance
        val instance = createInstance(requiredExtensions)
        val debugCallback = object : VkDebugReportCallbackEXT() {
            override fun invoke(flags: Int, objectType: Int, `object`: Long, location: Long, messageCode: Int, pLayerPrefix: Long, pMessage: Long, pUserData: Long): Int {
                System.err.println("ERROR OCCURED: " + VkDebugReportCallbackEXT.getString(pMessage))
                return 0
            }
        }
        val debugCallbackHandle = setupDebugging(instance, VK_DEBUG_REPORT_ERROR_BIT_EXT or VK_DEBUG_REPORT_WARNING_BIT_EXT, debugCallback)
        val physicalDevice = getFirstPhysicalDevice(instance)
        val deviceAndGraphicsQueueFamily = createDeviceAndGetGraphicsQueueFamily(physicalDevice)
        val device = deviceAndGraphicsQueueFamily.device
        val queueFamilyIndex = deviceAndGraphicsQueueFamily.queueFamilyIndex
        val memoryProperties = deviceAndGraphicsQueueFamily.memoryProperties

        // Create GLFW window
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        val window = glfwCreateWindow(800, 600, "GLFW Vulkan Demo", NULL, NULL)
        val keyCallback: GLFWKeyCallback = object : GLFWKeyCallback() {
            override fun invoke(window: Long, key: Int, scancode: Int, action: Int, mods: Int) {
                if (action != GLFW_RELEASE)
                    return
                if (key == GLFW_KEY_ESCAPE)
                    glfwSetWindowShouldClose(window, true)
            }
        }

        glfwSetKeyCallback(window, keyCallback)
        val pSurface = memAllocLong(1)
        var err = glfwCreateWindowSurface(instance, window, null, pSurface)
        val surface = pSurface.get(0)
        if (err != VK_SUCCESS) {
            throw AssertionError("Failed to create surface: " + translateVulkanResult(err))
        }

        // Create static Vulkan resources
        val colorFormatAndSpace = getColorFormatAndSpace(physicalDevice, surface)
        val commandPool = createCommandPool(device, queueFamilyIndex)
        val setupCommandBuffer = createCommandBuffer(device, commandPool)
        val postPresentCommandBuffer = createCommandBuffer(device, commandPool)
        val queue = createDeviceQueue(device, queueFamilyIndex)
        val renderPass = createRenderPass(device, colorFormatAndSpace.colorFormat)
        val renderCommandPool = createCommandPool(device, queueFamilyIndex)
        val vertices = createVertices(memoryProperties, device)
        val pipeline = createPipeline(device, renderPass, vertices.createInfo)

        class SwapchainRecreator {
            var mustRecreate = true
            fun recreate() {
                // Begin the setup command buffer (the one we will use for swapchain/framebuffer creation)
                val cmdBufInfo = VkCommandBufferBeginInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .pNext(NULL)
                var err = vkBeginCommandBuffer(setupCommandBuffer, cmdBufInfo)
                cmdBufInfo.free()
                if (err != VK_SUCCESS) {
                    throw AssertionError("Failed to begin setup command buffer: " + translateVulkanResult(err))
                }
                val oldChain = if (swapchain != null) swapchain!!.swapchainHandle else VK_NULL_HANDLE
                // Create the swapchain (this will also add a memory barrier to initialize the framebuffer images)
                swapchain = createSwapChain(device, physicalDevice, surface, oldChain, setupCommandBuffer,
                        width, height, colorFormatAndSpace.colorFormat, colorFormatAndSpace.colorSpace)
                err = vkEndCommandBuffer(setupCommandBuffer)
                if (err != VK_SUCCESS) {
                    throw AssertionError("Failed to end setup command buffer: " + translateVulkanResult(err))
                }
                submitCommandBuffer(queue, setupCommandBuffer)
                vkQueueWaitIdle(queue)

                if (framebuffers != null) {
                    for (i in framebuffers!!.indices)
                        vkDestroyFramebuffer(device!!, framebuffers!![i], null)
                }
                framebuffers = createFramebuffers(device, swapchain!!, renderPass, width, height)
                // Create render command buffers
                if (renderCommandBuffers != null) {
                    vkResetCommandPool(device!!, renderCommandPool, VK_FLAGS_NONE)
                }
                renderCommandBuffers = createRenderCommandBuffers(device, renderCommandPool, framebuffers!!, renderPass, width, height, pipeline,
                        vertices.verticesBuf)

                mustRecreate = false
            }
        }

        val swapchainRecreator = SwapchainRecreator()

        // Handle canvas resize
        val windowSizeCallback = object : GLFWWindowSizeCallback() {
            override fun invoke(window: Long, width: Int, height: Int) {
                if (width <= 0 || height <= 0)
                    return
                TriangleDemoKt.width = width
                TriangleDemoKt.height = height
                swapchainRecreator.mustRecreate = true
            }
        }
        glfwSetWindowSizeCallback(window, windowSizeCallback)
        glfwShowWindow(window)

        // Pre-allocate everything needed in the render loop

        val pImageIndex = memAllocInt(1)
        var currentBuffer = 0
        val pCommandBuffers = memAllocPointer(1)
        val pSwapchains = memAllocLong(1)
        val pImageAcquiredSemaphore = memAllocLong(1)
        val pRenderCompleteSemaphore = memAllocLong(1)

        // Info struct to create a semaphore
        val semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                .pNext(NULL)
                .flags(VK_FLAGS_NONE)

        // Info struct to submit a command buffer which will wait on the semaphore
        val pWaitDstStageMask = memAllocInt(1)
        pWaitDstStageMask.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
        val submitInfo = VkSubmitInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pNext(NULL)
                .waitSemaphoreCount(pImageAcquiredSemaphore.remaining())
                .pWaitSemaphores(pImageAcquiredSemaphore)
                .pWaitDstStageMask(pWaitDstStageMask)
                .pCommandBuffers(pCommandBuffers)
                .pSignalSemaphores(pRenderCompleteSemaphore)

        // Info struct to present the current swapchain image to the display
        val presentInfo = VkPresentInfoKHR.calloc()
                .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pNext(NULL)
                .pWaitSemaphores(pRenderCompleteSemaphore)
                .swapchainCount(pSwapchains.remaining())
                .pSwapchains(pSwapchains)
                .pImageIndices(pImageIndex)
                .pResults(null)

        // The render loop
        while (!glfwWindowShouldClose(window)) {
            // Handle window messages. Resize events happen exactly here.
            // So it is safe to use the new swapchain images and framebuffers afterwards.
            glfwPollEvents()
            if (swapchainRecreator.mustRecreate)
                swapchainRecreator.recreate()

            // Create a semaphore to wait for the swapchain to acquire the next image
            err = vkCreateSemaphore(device!!, semaphoreCreateInfo, null, pImageAcquiredSemaphore)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to create image acquired semaphore: " + translateVulkanResult(err))
            }

            // Create a semaphore to wait for the render to complete, before presenting
            err = vkCreateSemaphore(device, semaphoreCreateInfo, null, pRenderCompleteSemaphore)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to create render complete semaphore: " + translateVulkanResult(err))
            }

            // Get next image from the swap chain (back/front buffer).
            // This will setup the imageAquiredSemaphore to be signalled when the operation is complete
            err = vkAcquireNextImageKHR(device, swapchain!!.swapchainHandle, UINT64_MAX, pImageAcquiredSemaphore.get(0), VK_NULL_HANDLE, pImageIndex)
            currentBuffer = pImageIndex.get(0)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to acquire next swapchain image: " + translateVulkanResult(err))
            }

            // Select the command buffer for the current framebuffer image/attachment
            pCommandBuffers.put(0, renderCommandBuffers!![currentBuffer])

            // Submit to the graphics queue
            err = vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to submit render queue: " + translateVulkanResult(err))
            }

            // Present the current buffer to the swap chain
            // This will display the image
            pSwapchains.put(0, swapchain!!.swapchainHandle)
            err = vkQueuePresentKHR(queue, presentInfo)
            if (err != VK_SUCCESS) {
                throw AssertionError("Failed to present the swapchain image: " + translateVulkanResult(err))
            }
            // Create and submit post present barrier
            vkQueueWaitIdle(queue)

            // Destroy this semaphore (we will create a new one in the next frame)
            vkDestroySemaphore(device, pImageAcquiredSemaphore.get(0), null)
            vkDestroySemaphore(device, pRenderCompleteSemaphore.get(0), null)
            submitPostPresentBarrier(swapchain!!.images!![currentBuffer], postPresentCommandBuffer, queue)
        }
        presentInfo.free()
        memFree(pWaitDstStageMask)
        submitInfo.free()
        memFree(pImageAcquiredSemaphore)
        memFree(pRenderCompleteSemaphore)
        semaphoreCreateInfo.free()
        memFree(pSwapchains)
        memFree(pCommandBuffers)

        vkDestroyDebugReportCallbackEXT(instance, debugCallbackHandle, null)

        windowSizeCallback.free()
        keyCallback.free()
        glfwDestroyWindow(window)
        glfwTerminate()

        // We don't bother disposing of all Vulkan resources.
        // Let the OS process manager take care of it.
    }

}
